(ns bb-build.scaffold-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bb-build.scaffold :as scaffold]
            [babashka.fs :as fs]))

(def mit-text "MIT License\n\nCopyright (c) 2026 hive-agi\n\nPermission is hereby granted, free of charge, ...")

(defn- plan*
  "`scaffold/plan` for a public repo whose ./LICENSE is MIT, unless `m` says
  otherwise. The gates themselves are exercised separately below."
  [m]
  (scaffold/plan (merge {:visibility "public" :license-file-text mit-text} m)))

(deftest publishable?-rejects-git-and-local-runtime-deps
  (testing "pure mvn runtime deps are publishable"
    (is (scaffold/publishable?
         "{:deps {org.clojure/clojure {:mvn/version \"1.12.1\"}}\n :aliases {}}")))
  (testing "a git/tag RUNTIME dep is refused"
    (is (not (scaffold/publishable?
              "{:deps {x/y {:git/tag \"v1\" :git/sha \"abc\"}}}"))))
  (testing "a local/root RUNTIME dep is refused"
    (is (not (scaffold/publishable?
              "{:deps {x/y {:local/root \"../y\"}}}"))))
  (testing "git/local coords under :aliases do NOT block — they are pom-excluded"
    (is (scaffold/publishable?
         (str "{:deps {org.clojure/clojure {:mvn/version \"1.12.1\"}}\n"
              " :aliases {:test {:extra-deps {t/r {:git/tag \"v1\" :git/sha \"a\"}}}}}")))))

(deftest plan-clojars-kind
  (let [{:keys [kind version-edn workflow build-alias]}
        (plan* {:lib "hive-help" :target "/tmp/nope" :kind :clojars :minor 3})]
    (is (= :clojars kind))
    (testing "version.edn carries the rendered coord, minor, MIT, github scm, :clojars"
      (is (str/includes? (:content version-edn) "io.github.hive-agi/hive-help"))
      (is (str/includes? (:content version-edn) ":minor    3"))
      (is (str/includes? (:content version-edn) "MIT"))
      (is (str/includes? (:content version-edn) "github.com/hive-agi/hive-help"))
      (is (str/includes? (:content version-edn) ":publish  :clojars")))
    (testing "workflow lands under .github/workflows and is the Clojars variant"
      (is (str/includes? (:path workflow) ".github/workflows/release.yml"))
      (is (str/includes? (:content workflow) "CLOJARS_DEPLOY_TOKEN")))
    (testing "the :build alias consumes hive-build.api, not a local build.clj"
      (is (str/includes? build-alias "hive-build.api"))
      (is (not (str/includes? build-alias "build.clj"))))))

(deftest plan-gitea-kind
  (let [{:keys [version-edn workflow]}
        (plan* {:lib "hive-premium" :target "/tmp/nope" :kind :gitea})]
    (is (str/includes? (:content version-edn) ":publish  :gitea"))
    (is (str/includes? (:content version-edn) "gitea.hive-mcp.com/hive-agi/hive-premium"))
    (is (str/includes? (:path workflow) ".gitea/workflows/release.yml"))
    (is (str/includes? (:content workflow) "MAVEN_TOKEN"))))

(deftest release-workflows-release-from-tags
  (doseq [kind [:clojars :gitea]]
    (let [wf (:content (:workflow (plan* {:lib "x" :target "/tmp/nope" :kind kind})))]
      (testing (str kind " workflow fires on a version.edn or VERSION change")
        (is (str/includes? wf "- 'version.edn'"))
        (is (str/includes? wf "- 'VERSION'")))
      (testing (str kind " workflow pushes a tag only, never a bump commit to main")
        (is (str/includes? wf "git push origin \"refs/tags/v$VER\""))
        (is (not (str/includes? wf "git commit")))
        (is (str/includes? wf "git tag -l 'v[0-9]*'"))))))

(deftest release-workflows-tag-only-after-deploy
  (doseq [kind [:clojars :gitea]]
    (let [wf (:content (:workflow (scaffold/plan {:lib "x" :target "/tmp/nope" :kind kind})))
          deploy-at (str/index-of wf "clojure -T:build deploy")
          tag-at (str/index-of wf "git tag -a")]
      (testing (str kind " workflow deploys, then tags, so a failed deploy leaves no orphan tag")
        (is (some? deploy-at))
        (is (some? tag-at))
        (is (< deploy-at tag-at))))))

(deftest plan-license-override
  (let [{:keys [version-edn]}
        (plan* {:lib "x" :target "/tmp/nope" :kind :clojars
                        :license "EPL-2.0" :license-url "https://www.eclipse.org/legal/epl-2.0/"})]
    (is (str/includes? (:content version-edn) "EPL-2.0"))))

(deftest github-owner-parses-remotes
  (is (= "BuddhiLW" (scaffold/github-owner "git@github.com:BuddhiLW/cleanx.git")))
  (is (= "hive-agi" (scaffold/github-owner "https://github.com/hive-agi/hive-build.git\n")))
  (is (= "hive-agi" (scaffold/github-owner "ssh://git@github.com/hive-agi/hive-build")))
  (is (nil? (scaffold/github-owner "git@gitea.hive-mcp.com:hive-agi/hive-premium.git")))
  (is (nil? (scaffold/github-owner nil))))

(deftest plan-defaults-to-hive-agi-group
  (is (= "io.github.hive-agi"
         (:group (plan* {:lib "hive-help" :target "/tmp/nope" :kind :clojars})))))

(deftest plan-derives-group-from-github-owner
  (let [{:keys [group version-edn]}
        (plan* {:lib "cleanx" :target "/tmp/nope" :kind :clojars :scm-owner "BuddhiLW"})]
    (testing "group is io.github.<owner>, lower-cased"
      (is (= "io.github.buddhilw" group))
      (is (str/includes? (:content version-edn) "io.github.buddhilw/cleanx")))
    (testing "scm-url keeps the owner's spelling"
      (is (str/includes? (:content version-edn) "\"https://github.com/BuddhiLW/cleanx\"")))))

(deftest plan-explicit-group-wins-over-owner
  (let [{:keys [group version-edn]}
        (plan* {:lib "x" :target "/tmp/nope" :kind :clojars
                        :scm-owner "BuddhiLW" :group "net.clojars.buddhilw"})]
    (is (= "net.clojars.buddhilw" group))
    (is (str/includes? (:content version-edn) "net.clojars.buddhilw/x"))
    (is (str/includes? (:content version-edn) "https://github.com/BuddhiLW/x"))))

(deftest plan-gitea-ignores-github-owner
  (let [{:keys [group version-edn]}
        (plan* {:lib "p" :target "/tmp/nope" :kind :gitea :scm-owner "BuddhiLW"})]
    (is (= "io.github.hive-agi" group))
    (is (str/includes? (:content version-edn) "gitea.hive-mcp.com/hive-agi/p"))))

(deftest plan-rejects-unknown-kind
  (is (thrown? clojure.lang.ExceptionInfo
               (plan* {:lib "x" :target "/tmp/nope" :kind :svn}))))

(defn- scratch-target []
  (let [dir (fs/create-temp-dir {:prefix "bb-build-test"})]
    (spit (str (fs/path dir "deps.edn")) "{:deps {}}")
    (str dir)))

(defn- status-of [results path]
  (:status (first (filter #(= path (:path %)) results))))

(deftest plan-includes-version-file
  (let [{:keys [version-file]}
        (plan* {:lib "x" :target "/tmp/nope" :kind :clojars})]
    (is (str/ends-with? (:path version-file) "/tmp/nope/VERSION"))
    (is (= "0.1.0\n" (:content version-file)))))

(deftest apply!-writes-version-when-absent
  (let [target (scratch-target)]
    (try
      (let [p       (plan* {:lib "x" :target target :kind :clojars})
            path    (get-in p [:version-file :path])
            results (scaffold/apply! p {})]
        (is (= :written (status-of results path)))
        (is (= "0.1.0\n" (slurp path)))
        (is (= :written (status-of results (get-in p [:version-edn :path])))))
      (finally (fs/delete-tree target)))))

(deftest apply!-keeps-existing-version-even-under-force
  (let [target (scratch-target)
        path   (str (fs/path target "VERSION"))]
    (try
      (spit path "0.4.2\n")
      (let [p (plan* {:lib "x" :target target :kind :clojars})]
        (testing "without :force"
          (is (= :skipped-exists (status-of (scaffold/apply! p {}) path)))
          (is (= "0.4.2\n" (slurp path))))
        (testing "with :force, VERSION still survives while version.edn is rewritten"
          (let [results (scaffold/apply! p {:force true})]
            (is (= :skipped-exists (status-of results path)))
            (is (= :written (status-of results (get-in p [:version-edn :path]))))
            (is (= "0.4.2\n" (slurp path))))))
      (finally (fs/delete-tree target)))))

(deftest every-release-template-gates-on-the-licence-before-the-bump
  ;; LICENSE-CI-GATE: verify-license used to run nowhere, so a licence could
  ;; drift for months. Every stamped release workflow must run it strictly,
  ;; and before the bump, since a published pom can never be retracted.
  (doseq [t ["release-clojars.yml" "release-gitea.yml"]
          :let [body (slurp (clojure.java.io/resource (str "bb_build/templates/" t)))
                gate (str/index-of body "clojure -T:build verify-license :strict true")
                bump (str/index-of body "clojure -T:build bump")]]
    (testing t
      (is (some? gate) "runs verify-license strictly")
      (is (and gate bump (< gate bump)) "before the version is minted")
      (is (str/includes? body "WARNING: license inconsistency")
          "an older pinned hive-build that only warns still fails the step"))))

;; ── CLOJARS-INFRA gates: no invented licence, no private repo on Clojars ──

(defn- refusal [m]
  (try (scaffold/plan (merge {:lib "x" :target "/tmp/nope" :kind :clojars} m))
       nil
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest no-licence-is-ever-defaulted
  (testing "neither --license nor ./LICENSE: refuse rather than write MIT"
    (is (= :license/missing (refusal {:visibility "public"}))))
  (testing "the gitea kind no longer defaults to Proprietary either"
    (is (= :license/missing (refusal {:kind :gitea}))))
  (testing "a LICENSE that names nothing recognisable is refused, not guessed"
    (is (= :license/unrecognized (refusal {:visibility "public"
                                           :license-file-text "do what you like"}))))
  (testing "an explicit unknown licence needs its URL"
    (is (= :license/url-missing (refusal {:visibility "public" :license "WTFPL"})))))

(deftest the-licence-is-read-from-the-license-file
  (doseq [[text expected] [[mit-text "MIT"]
                           ["Eclipse Public License - v 2.0\n..." "EPL-2.0"]
                           ["                                 Apache License\n Version 2.0" "Apache-2.0"]
                           ["Copyright hive-agi. All rights reserved." "Proprietary"]]]
    (let [{:keys [version-edn]} (plan* {:lib "x" :target "/tmp/nope" :kind :clojars
                                        :license-file-text text})]
      (is (str/includes? (:content version-edn) (str "\"" expected "\"")) expected))))

(deftest only-a-public-repo-is-scaffolded-for-clojars
  (doseq [v [nil "private" "internal" "PRIVATE"]]
    (is (= :visibility/not-public (refusal {:visibility v :license-file-text mit-text}))
        (str "visibility " (pr-str v))))
  (testing "visibility is the forge's answer in any case"
    (is (nil? (refusal {:visibility "PUBLIC" :license-file-text mit-text}))))
  (testing "a private Gitea kind does not ask"
    (is (nil? (refusal {:kind :gitea :visibility nil :license-file-text mit-text})))))
