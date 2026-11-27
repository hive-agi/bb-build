(ns bb-build.scaffold
  "lein-new-style generator for a hive project's build setup.

  `plan` is pure: given a target repo and a template kind it computes the file
  writes (version.edn, the release workflow, the :build alias snippet). `apply!`
  performs them. hive-build stays a pure library — a consumer gets the :build
  alias that consumes `hive-build.api`, never a copied build.clj."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-group "io.github.hive-agi")

(defn github-owner
  "Owner segment of a github.com remote URL (scp-style, ssh:// or https), or nil."
  [remote-url]
  (when remote-url
    (second (re-find #"github\.com[:/]([^/\s]+)/" remote-url))))

(defn owner-group
  "Clojars verified group for a GitHub owner: io.github.<owner>, lower-cased."
  [owner]
  (str "io.github." (str/lower-case owner)))

;; Template kinds. The publish target drives license default, SCM host, the
;; release workflow, and its CI directory — never both .github and .gitea.
(def kinds
  {:clojars {:publish  :clojars
             :scm-host "github.com/hive-agi"
             :workflow "release-clojars.yml"
             :ci-dir   ".github/workflows"
             :public?  true}
   :gitea   {:publish  :gitea
             :scm-host "gitea.hive-mcp.com/hive-agi"
             :workflow "release-gitea.yml"
             :ci-dir   ".gitea/workflows"
             :public?  false}})

(def known-licenses
  "Licences a LICENSE file is recognised as, in match order: the name written
  into version.edn, its canonical URL, and text that identifies it."
  [{:name "EPL-2.0" :url "https://www.eclipse.org/legal/epl-2.0/"
    :markers ["SPDX-License-Identifier: EPL-2.0" "Eclipse Public License - v 2.0"
              "Eclipse Public License - Version 2.0"]}
   {:name "Apache-2.0" :url "https://www.apache.org/licenses/LICENSE-2.0"
    :markers ["SPDX-License-Identifier: Apache-2.0" "Apache License" ]}
   {:name "MIT" :url "https://opensource.org/licenses/MIT"
    :markers ["SPDX-License-Identifier: MIT" "MIT License"
              "Permission is hereby granted, free of charge"]}
   {:name "Proprietary" :url "https://gitea.hive-mcp.com/hive-agi"
    :markers ["Proprietary" "All rights reserved" "All Rights Reserved"]}])

(defn license-from-text
  "The known licence `license-file-text` (a ./LICENSE body) declares, or nil."
  [license-file-text]
  (when-not (str/blank? license-file-text)
    (first (filter (fn [{:keys [markers]}]
                     (some #(str/includes? license-file-text %) markers))
                   known-licenses))))

(defn resolve-license
  "Pure. {:name :url} for the scaffold: an explicit `license` wins, else what
  ./LICENSE declares. Never a default: throws :license/missing when there is no
  licence to declare, :license/unrecognized when ./LICENSE names none we know,
  and :license/url-missing for an explicit unknown licence without a URL."
  [{:keys [license license-url license-file-text]}]
  (cond
    (seq license)
    (let [url (or license-url
                  (:url (first (filter #(= license (:name %)) known-licenses))))]
      (when-not url
        (throw (ex-info (str "--license " license " needs --license-url")
                        {:reason :license/url-missing :license license})))
      {:name license :url url})

    (str/blank? license-file-text)
    (throw (ex-info "no ./LICENSE and no --license: refusing to invent a licence"
                    {:reason :license/missing}))

    :else
    (if-let [{:keys [name url]} (license-from-text license-file-text)]
      {:name name :url url}
      (throw (ex-info "./LICENSE names no licence bb-build recognises; pass --license and --license-url"
                      {:reason :license/unrecognized})))))

(defn refuse-non-public!
  "Pure gate. A kind that publishes publicly (Clojars) requires the repo to be
  public; `visibility` is the forge's answer (any case), nil when unknown.
  Throws :visibility/not-public otherwise."
  [kind-spec visibility]
  (when (and (:public? kind-spec)
             (not= "public" (some-> visibility str/lower-case)))
    (throw (ex-info (str "refusing a public-registry scaffold for a repo whose visibility is "
                         (or visibility "unknown") "; only public repos publish to Clojars")
                    {:reason :visibility/not-public :visibility visibility}))))

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

(def initial-version
  "Content of a freshly scaffolded VERSION file (hive-build's patch-bump source)."
  "0.1.0\n")

(defn plan
  "Pure. Compute the scaffold for `lib` into `target` under template `kind`.
  :scm-owner (a GitHub owner, :clojars only) sets the scm-url owner and a
  default group of io.github.<owner>; an explicit :group wins over both.
  Returns {:kind :group :version-edn {:path :content}
  :version-file {:path :content :keep-existing? true} :workflow {:path :content}
  :build-alias str}. Throws on an unknown kind.
  The licence comes from :license/:license-url or :license-file-text (the
  target's ./LICENSE), never a default (see `resolve-license`); a public kind
  requires :visibility \"public\" (see `refuse-non-public!`)."
  [{:keys [lib target kind minor src-dirs group scm-owner visibility] :as opts
    :or   {minor 1}}]
  (let [k (or (get kinds kind)
              (throw (ex-info (str "unknown kind: " kind)
                              {:kind kind :known (vec (keys kinds))})))
        _        (refuse-non-public! k visibility)
        lic      (resolve-license opts)
        owner    (when (= :clojars kind) scm-owner)
        group    (or group (some-> owner owner-group) default-group)
        scm-host (if owner (str "github.com/" owner) (:scm-host k))
        lic-name (:name lic)
        lic-url  (:url lic)
        src-dirs (or src-dirs (default-src-dirs target))
        subs {:lib lib :group group :minor minor
              :license-name lic-name :license-url lic-url
              :scm-url (str "https://" scm-host "/" lib)
              :src-dirs src-dirs :publish (:publish k)}]
    {:kind kind
     :group group
     :version-edn  {:path    (str (fs/path (str target) "version.edn"))
                    :content (render (slurp (resource "bb_build/templates/version.edn.tmpl")) subs)}
     :version-file {:path           (str (fs/path (str target) "VERSION"))
                    :content        initial-version
                    :keep-existing? true}
     :workflow     {:path    (str (fs/path (str target) (:ci-dir k) "release.yml"))
                    :content (slurp (resource (str "bb_build/templates/" (:workflow k))))}
     :build-alias (slurp (resource "bb_build/templates/build-alias.edn"))}))

(defn apply!
  "Effectful. Write the planned files. Skips a file that already exists unless
  :force; a file planned with :keep-existing? (VERSION) is never overwritten,
  even under :force. Returns [{:path :status}] where status is
  :written | :skipped-exists.
  Does NOT edit deps.edn — the :build alias is returned for the caller to inject
  or print, because editing a hand-maintained deps.edn is not safe to automate."
  [{:keys [version-edn version-file workflow]} {:keys [force]}]
  (mapv (fn [{:keys [path content keep-existing?]}]
          (if (and (fs/exists? path) (or keep-existing? (not force)))
            {:path path :status :skipped-exists}
            (do (fs/create-dirs (fs/parent path))
                (spit path content)
                {:path path :status :written})))
        (remove nil? [version-edn version-file workflow])))
