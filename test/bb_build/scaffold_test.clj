(ns bb-build.scaffold-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bb-build.scaffold :as scaffold]))

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
        (scaffold/plan {:lib "hive-help" :target "/tmp/nope" :kind :clojars :minor 3})]
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
        (scaffold/plan {:lib "hive-premium" :target "/tmp/nope" :kind :gitea})]
    (is (str/includes? (:content version-edn) ":publish  :gitea"))
    (is (str/includes? (:content version-edn) "gitea.hive-mcp.com/hive-agi/hive-premium"))
    (is (str/includes? (:path workflow) ".gitea/workflows/release.yml"))
    (is (str/includes? (:content workflow) "MAVEN_TOKEN"))))

(deftest plan-license-override
  (let [{:keys [version-edn]}
        (scaffold/plan {:lib "x" :target "/tmp/nope" :kind :clojars
                        :license "EPL-2.0" :license-url "https://www.eclipse.org/legal/epl-2.0/"})]
    (is (str/includes? (:content version-edn) "EPL-2.0"))))

(deftest plan-rejects-unknown-kind
  (is (thrown? clojure.lang.ExceptionInfo
               (scaffold/plan {:lib "x" :target "/tmp/nope" :kind :svn}))))
