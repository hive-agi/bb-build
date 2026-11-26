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
  "Write every rendered template for `forge` into `dir`, since a clean repo is
  one that already holds byte-identical copies."
  [dir forge ci-dir]
  (doseq [[name text] (ci/rendered-templates forge)]
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

;; ── drift and sync over fixture repos ─────────────────────────────────────

(deftest a-clean-repo-passes
  (doseq [[forge ci-dir] [[:github ".github/workflows"] [:gitea ".gitea/workflows"]]]
    (let [dir (managed-into (fixture ci-dir {}) forge ci-dir)]
      (testing (str forge " clean")
        (is (empty? (ci/drift-findings (ci/rendered-templates forge)
                                       (ci/read-workflows dir forge))))
        (is (= 0 (:exit (p/sh {:continue true} "bb" "ci:drift" dir
                               :dir repo-root))))))))

(deftest drift-on-an-edited-copy
  (let [forge :github
        dir   (managed-into (fixture ".github/workflows" {}) forge ".github/workflows")
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
               forge ".github/workflows")
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
                          :github ".github/workflows")]
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
