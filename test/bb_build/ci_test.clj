(ns bb-build.ci-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-build.ci :as ci]))

(def repo-root
  "The bb-build checkout the tests run in, so the real templates are exercised."
  (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))

;; ── fixture repo dirs, in a temp dir ────────────────────────────────────────

(defn- ok-text [& args]
  (:out (apply p/sh {:continue true} "bb" args)))

(defn- fixture
  "A temp repo dir holding a deps.edn and, per `files`, workflow files at
  <ci-dir>/<name>. Returns its path."
  [ci-dir files]
  (let [dir (str (fs/create-temp-dir {:prefix "bb-build-ci-"}))]
    (spit (str (fs/path dir "deps.edn")) "{:deps {}}")
    ;; The workflow dir itself must exist: it is what tells the two forges apart.
    (fs/create-dirs (fs/path dir ci-dir))
    (doseq [[name text] files]
      (let [f (fs/path dir ci-dir name)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) text)))
    (fs/delete-on-exit dir)
    dir))

(defn- managed-into
  "Write every rendered template for `forge` in `mode` into `dir`, since a
  clean repo is one that already holds byte-identical copies. A tracked VERSION
  file is what makes the repo pinned; without one it is rolling."
  [dir forge ci-dir mode]
  (when (= :pinned mode)
    (spit (str (fs/path dir "VERSION")) "0.1.0\n"))
  (doseq [[name text] (ci/rendered-templates forge mode)]
    (let [f (fs/path dir ci-dir name)]
      (fs/create-dirs (fs/parent f))
      (spit (str f) text)))
  dir)

;; ── pure core ────────────────────────────────────────────────────────────

(deftest render-is-byte-identical-for-every-repo
  (testing "a template renders to itself plus exactly one trailing newline"
    (is (= "a\nb\n" (ci/render "a\nb")))
    (is (= "a\nb\n" (ci/render "a\nb\n")))
    (is (= "a\nb\n" (ci/render "a\r\nb\r\n"))))
  (testing "rendering is idempotent, so a second sync cannot change the file"
    (doseq [forge [:github :gitea] [name text] (ci/rendered-templates forge)]
      (is (= text (ci/render (ci/render text))) name))))

(deftest action-pins-are-parsed-as-data
  (is (= {:action "actions/checkout" :major 7} (ci/action-ref "actions/checkout@v7")))
  (is (= {:action "actions/setup-java" :major 6} (ci/action-ref "actions/setup-java@v6.3.0")))
  (is (= {:action "DeLaGuardo/setup-clojure" :major 13}
         (ci/action-ref "DeLaGuardo/setup-clojure@13.7.0")))
  (testing "a SHA pin, a docker ref and a local path are not owner/name majors"
    (is (nil? (ci/action-ref "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1")))
    (is (nil? (ci/action-ref "docker://alpine:3.20")))
    (is (nil? (ci/action-ref "./.github/actions/x")))
    (is (nil? (ci/action-ref "")))))

(deftest action-majors-reports-the-lowest-pin-per-action
  (is (= {"actions/checkout" 4
          "actions/setup-java" 6}
         (ci/action-majors (str "      - uses: actions/checkout@v7\n"
                                "      - uses: actions/checkout@v4\n"
                                "        uses: actions/setup-java@v6\n"
                                "        run: echo uses: not-an-action@v1\n")))))

(deftest the-templates-pin-the-plan-targets
  (let [floors (ci/floors-for (vals (ci/rendered-templates :github)))]
    (is (= 7 (get floors "actions/checkout")))
    (is (= 6 (get floors "actions/setup-java")))
    (is (= 6 (get floors "actions/cache")))
    (is (= 13 (get floors "DeLaGuardo/setup-clojure")))))

(deftest every-template-pins-java-25-and-no-moving-tag
  (doseq [name ["staging-gate.yml" "release-clojars.yml" "release-gitea.yml"]
          :let [body (slurp (str (fs/path repo-root "resources/bb_build/templates" name)))]]
    (testing name
      (is (str/includes? body "java-version: '25'") "pins Java 25")
      (is (not (str/includes? body "cli: latest")) "never resolves a CLI from 'latest')")
      (is (not (str/includes? body "1.12.4.1618")) "not the old installer"))))

(deftest staging-gate-runs-bb-test-bb-only-when-bb-edn-declares-it
  (let [body (slurp (str (fs/path repo-root "resources/bb_build/templates/staging-gate.yml")))]
    (testing "the bb step is guarded on the task, not on the file alone"
      (is (str/includes? body "grep -qF 'test:bb' bb.edn")))
    (testing "bb is installed at a pinned version, never 'latest'"
      (is (str/includes? body "BABASHKA_VERSION: 1.13.230"))
      (is (not (str/includes? body "babashka/master/install"))))
    (testing "the Clojure CLI is pinned"
      (is (str/includes? body "CLOJURE_CLI_VERSION: 1.12.7.1681")))))

(deftest staging-gate-runs-the-licence-gate-only-when-deps-edn-declares-build
  (let [body (slurp (str (fs/path repo-root "resources/bb_build/templates/staging-gate.yml")))]
    (testing "the licence step is guarded on the :build alias, not assumed"
      (is (str/includes? body "[:aliases :build]"))
      (is (str/includes? body "no :build alias in deps.edn; skipping")))
    (testing "and it still stops the run on real licence drift"
      (is (str/includes? body "clojure -T:build verify-license :strict true")))))

;; ── drift and sync over fixture repos ─────────────────────────────────────

(deftest a-clean-repo-passes
  (doseq [forge [:github :gitea]
          mode  [:pinned :rolling]
          :let  [ci-dir (if (= :gitea forge) ".gitea/workflows" ".github/workflows")]]
    (let [dir (managed-into (fixture ci-dir {}) forge ci-dir mode)]
      (testing (str (name forge) " " (name mode) " clean")
        (is (empty? (ci/drift-findings (ci/rendered-templates forge mode)
                                       (ci/read-workflows dir forge))))
        (is (= 0 (:exit (p/sh {:continue true} "bb" "ci:drift" dir
                               :dir repo-root))))))))

(deftest drift-on-an-edited-copy
  (let [forge :github
        dir   (managed-into (fixture ".github/workflows" {}) forge ".github/workflows" :pinned)
        f     (str (fs/path dir ".github/workflows/staging-gate.yml"))]
    (spit f (str (slurp f) "\n# hand-edited\n"))
    (let [findings (ci/drift-findings (ci/rendered-templates forge)
                                      (ci/read-workflows dir forge))]
      (is (= [{:kind :differs :path "staging-gate.yml"}] (vec findings)))
      (is (= 1 (:exit (p/sh {:continue true} "bb" "ci:drift" dir :dir repo-root))))
      (is (str/includes? (ok-text "ci:drift" dir :dir repo-root)
                         "staging-gate.yml: differs from its template")))))

(deftest drift-on-a-missing-copy
  (let [dir (fixture ".github/workflows" {"staging-gate.yml" "name: x\n"})]
    (is (some #(= :missing (:kind %))
              (ci/drift-findings (ci/rendered-templates :github)
                                 (ci/read-workflows dir :github))))))

(deftest drift-on-an-old-major-in-a-hand-written-file
  (let [forge :github
        dir   (managed-into
               (fixture ".github/workflows"
                        {"hand.yml" (str "name: hand\n"
                                        "jobs:\n"
                                        "  a:\n"
                                        "    steps:\n"
                                        "      - uses: actions/checkout@v4\n"
                                        "      - uses: actions/setup-node@v7\n")})
               forge ".github/workflows" :pinned)
        findings (ci/drift-findings (ci/rendered-templates forge)
                                    (ci/read-workflows dir forge))]
    (testing "only the below-floor pin is a finding, and the file is named"
      (is (= [{:kind :major :path "hand.yml" :action "actions/checkout"
               :found 4 :wanted 7}]
             (vec findings)))
      (is (= 1 (:exit (p/sh {:continue true} "bb" "ci:drift" dir :dir repo-root))))
      (is (str/includes? (ok-text "ci:drift" dir :dir repo-root)
                         "hand.yml: actions/checkout is pinned at v4, the template pins v7")))))

(deftest an-unmanaged-file-is-never-touched
  (let [dir (managed-into (fixture ".github/workflows" {"notes.yml" "custom\n"})
                          :github ".github/workflows" :pinned)]
    (p/sh {:continue true} "bb" "ci:sync" dir "--write" :dir repo-root)
    (is (= "custom\n" (slurp (str (fs/path dir ".github/workflows/notes.yml")))))))

;; ── sync: dry run by default ──────────────────────────────────────────────

(deftest sync-without-write-changes-nothing
  (let [dir  (fixture ".github/workflows" {"staging-gate.yml" "name: stale\n"})
        f    (str (fs/path dir ".github/workflows/staging-gate.yml"))
        out  (ok-text "ci:sync" dir :dir repo-root)]
    (testing "the file on disk is exactly as it was"
      (is (= "name: stale\n" (slurp f))))
    (testing "a diff and a re-run hint are printed instead"
      (is (str/includes? out "--- a/staging-gate.yml"))
      (is (str/includes? out "+++ b/staging-gate.yml"))
      (is (str/includes? out "re-run with --write")))))

(deftest sync-with-write-is-idempotent
  (let [dir (fixture ".github/workflows" {})
        f   (str (fs/path dir ".github/workflows/staging-gate.yml"))]
    (ok-text "ci:sync" dir "--write" :dir repo-root)
    (let [first-text (slurp f)]
      (is (= (get (ci/rendered-templates :github) "staging-gate.yml") first-text))
      (testing "a second sync reports nothing to do and rewrites nothing"
        (let [before (fs/last-modified-time f)
              out    (ok-text "ci:sync" dir "--write" :dir repo-root)]
          (is (str/includes? out "already match their templates"))
          (is (= first-text (slurp f)))
          (is (= before (fs/last-modified-time f))))))
    (testing "and drift is green afterwards"
      (is (= 0 (:exit (p/sh {:continue true} "bb" "ci:drift" dir :dir repo-root)))))))

(deftest sync-picks-the-gitea-dir-from-the-repo-layout
  (let [dir (fixture ".gitea/workflows" {})]
    (ok-text "ci:sync" dir "--write" :dir repo-root)
    (is (fs/exists? (str (fs/path dir ".gitea/workflows/release.yml"))))
    (is (not (fs/exists? (str (fs/path dir ".github")))))
    (testing "the gitea release template is the Maven one, not the Clojars one"
      (is (str/includes? (slurp (str (fs/path dir ".gitea/workflows/release.yml")))
                         "MAVEN_TOKEN")))))

;; ── release mode: pinned vs rolling ──────────────────────────────────────

(deftest release-mode-follows-the-tracked-version-file
  (testing "a fresh repo with no VERSION is rolling"
    (is (= :rolling (ci/release-mode (fixture ".gitea/workflows" {})))))
  (let [dir (fixture ".gitea/workflows" {})]
    (spit (str (fs/path dir "VERSION")) "0.2.0\n")
    (is (= :pinned (ci/release-mode dir)))
    (fs/delete (fs/path dir "VERSION"))
    (is (= :rolling (ci/release-mode dir)))))

(deftest the-mode-chooses-the-release-template
  (testing "gitea has both templates and renders the one the mode names"
    (is (str/includes? (get (ci/rendered-templates :gitea :rolling) "release.yml")
                       "ROLLING release"))
    (is (str/includes? (get (ci/rendered-templates :gitea :pinned) "release.yml")
                       "PINNED release"))
    (is (not= (get (ci/rendered-templates :gitea :rolling) "release.yml")
              (get (ci/rendered-templates :gitea :pinned) "release.yml"))))
  (testing "staging-gate is one file either way"
    (is (= (get (ci/rendered-templates :gitea :rolling) "staging-gate.yml")
           (get (ci/rendered-templates :gitea :pinned) "staging-gate.yml"))))
  (testing "github has one release template, so the mode changes nothing"
    (is (= (ci/rendered-templates :github) (ci/rendered-templates :github :rolling)))))

(deftest an-unknown-release-mode-is-refused
  (is (thrown? clojure.lang.ExceptionInfo (ci/rendered-templates :gitea :sometimes))))

(deftest sync-renders-the-rolling-template-when-there-is-no-version
  (let [dir (fixture ".gitea/workflows" {})
        f   (str (fs/path dir ".gitea/workflows/release.yml"))
        out (ok-text "ci:sync" dir "--write" :dir repo-root)]
    (testing "the run says which mode it rendered"
      (is (str/includes? out "gitea, rolling")))
    (testing "the copy is the rolling template"
      (is (str/includes? (slurp f) "ROLLING release"))
      (is (not (str/includes? (slurp f) "Bump patch version"))))
    (testing "and drift is clean against the rolling template"
      (is (= 0 (:exit (p/sh {:continue true} "bb" "ci:drift" dir :dir repo-root)))))
    (testing "an explicit --mode still wins, for inspecting the other side"
      (is (str/includes? (ok-text "ci:sync" dir "--mode" "pinned" :dir repo-root)
                         "pinned")))))

(deftest sync-renders-the-pinned-template-when-a-version-is-tracked
  (let [dir (fixture ".gitea/workflows" {})]
    (spit (str (fs/path dir "VERSION")) "0.2.0\n")
    (let [f   (str (fs/path dir ".gitea/workflows/release.yml"))
          out (ok-text "ci:sync" dir "--write" :dir repo-root)]
      (testing "pinned mode is reported and renders the bump-and-tag template"
        (is (str/includes? out "gitea, pinned"))
        (is (str/includes? (slurp f) "PINNED release"))
        (is (str/includes? (slurp f) "Bump patch version")))
      (testing "drift is clean against the pinned template"
        (is (= 0 (:exit (p/sh {:continue true} "bb" "ci:drift" dir :dir repo-root))))))))

(deftest drift-judges-each-mode-against-its-own-template
  (testing "a rolling repo holding the pinned copy is drift, not a match"
    (let [dir (fixture ".gitea/workflows" {})]
      (doseq [[name text] (ci/rendered-templates :gitea :pinned)]
        (spit (str (fs/path dir ".gitea/workflows" name)) text))
      (is (= [{:kind :differs :path "release.yml"}]
             (vec (ci/drift-findings (ci/rendered-templates :gitea :rolling)
                                     (ci/read-workflows dir :gitea)))))
      (is (= 1 (:exit (p/sh {:continue true} "bb" "ci:drift" dir :dir repo-root)))
          "ci:drift reads the repo's own mode, so it selects rolling here")))
  (testing "and a pinned repo holding the rolling copy is drift too"
    (let [dir (fixture ".gitea/workflows" {})]
      (doseq [[name text] (ci/rendered-templates :gitea :rolling)]
        (spit (str (fs/path dir ".gitea/workflows" name)) text))
      (is (= [{:kind :differs :path "release.yml"}]
             (vec (ci/drift-findings (ci/rendered-templates :gitea :pinned)
                                     (ci/read-workflows dir :gitea))))))))
