(ns bb-build.scaffold
  "lein-new-style generator for a hive project's build setup.

  `plan` is pure: given a target repo and a template kind it computes the file
  writes (version.edn, the release workflow, the :build alias snippet). `apply!`
  performs them. hive-build stays a pure library — a consumer gets the :build
  alias that consumes `hive-build.api`, never a copied build.clj."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def group "io.github.hive-agi")

;; Template kinds. The publish target drives license default, SCM host, the
;; release workflow, and its CI directory — never both .github and .gitea.
(def kinds
  {:clojars {:publish  :clojars
             :license  {:name "MIT" :url "https://opensource.org/licenses/MIT"}
             :scm-host "github.com/hive-agi"
             :workflow "release-clojars.yml"
             :ci-dir   ".github/workflows"}
   :gitea   {:publish  :gitea
             :license  {:name "Proprietary" :url "https://gitea.hive-mcp.com/hive-agi"}
             :scm-host "gitea.hive-mcp.com/hive-agi"
             :workflow "release-gitea.yml"
             :ci-dir   ".gitea/workflows"}})

(defn- resource [path]
  (or (io/resource path)
      (throw (ex-info (str "template not found on classpath: " path) {:path path}))))

(defn- render [tmpl subs]
  (reduce (fn [s [k v]] (str/replace s (str "{{" (name k) "}}") (str v)))
          tmpl subs))

(defn- runtime-slice
  "The deps.edn text BEFORE :aliases — the runtime dependency surface. Aliases
  (:test/:dev/:build) are excluded from the pom, so their git/local coords are
  irrelevant to publishability."
  [deps-edn-text]
  (if-let [idx (str/index-of deps-edn-text ":aliases")]
    (subs deps-edn-text 0 idx)
    deps-edn-text))

(defn publishable?
  "A repo is publishable only if every RUNTIME dep is :mvn/version. :git/tag,
  :git/url, :git/sha and :local/root runtime coords cannot form a complete
  Maven pom, so a downstream consumer's graph would be incomplete."
  [deps-edn-text]
  (not (re-find #":local/root|:git/(?:tag|url|sha)" (runtime-slice deps-edn-text))))

(defn- default-src-dirs [target]
  (if (fs/directory? (fs/path (str target) "resources"))
    "[\"src\" \"resources\"]"
    "[\"src\"]"))

(defn plan
  "Pure. Compute the scaffold for `lib` into `target` under template `kind`.
  Returns {:kind :version-edn {:path :content} :workflow {:path :content}
  :build-alias str}. Throws on an unknown kind."
  [{:keys [lib target kind minor license license-url src-dirs]
    :or   {minor 1}}]
  (let [k (or (get kinds kind)
              (throw (ex-info (str "unknown kind: " kind)
                              {:kind kind :known (vec (keys kinds))})))
        lic-name (or license (get-in k [:license :name]))
        lic-url  (or license-url (get-in k [:license :url]))
        src-dirs (or src-dirs (default-src-dirs target))
        subs {:lib lib :group group :minor minor
              :license-name lic-name :license-url lic-url
              :scm-url (str "https://" (:scm-host k) "/" lib)
              :src-dirs src-dirs :publish (:publish k)}]
    {:kind kind
     :version-edn {:path    (str (fs/path (str target) "version.edn"))
                   :content (render (slurp (resource "bb_build/templates/version.edn.tmpl")) subs)}
     :workflow    {:path    (str (fs/path (str target) (:ci-dir k) "release.yml"))
                   :content (slurp (resource (str "bb_build/templates/" (:workflow k))))}
     :build-alias (slurp (resource "bb_build/templates/build-alias.edn"))}))

(defn apply!
  "Effectful. Write the planned files. Skips a file that already exists unless
  :force. Returns [{:path :status}] where status is :written | :skipped-exists.
  Does NOT edit deps.edn — the :build alias is returned for the caller to inject
  or print, because editing a hand-maintained deps.edn is not safe to automate."
  [{:keys [version-edn workflow]} {:keys [force]}]
  (mapv (fn [{:keys [path content]}]
          (if (and (fs/exists? path) (not force))
            {:path path :status :skipped-exists}
            (do (fs/create-dirs (fs/parent path))
                (spit path content)
                {:path path :status :written})))
        [version-edn workflow]))
