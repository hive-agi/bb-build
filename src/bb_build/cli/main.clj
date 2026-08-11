(ns bb-build.cli.main
  "`bb-build` — lein-new-style scaffolder for hive project build setups.

  Usage:
    bb-build new <lib-short-name> <target-dir> [opts]

  Writes version.edn + the release workflow into <target-dir> and prints the
  :build alias to add to its deps.edn. hive-build itself is a pure library:
  a scaffolded repo consumes hive-build.api, it never gets a copied build.clj."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [bb-build.scaffold :as scaffold]))

(def ^:private spec
  {:minor       {:desc "version.edn :minor (fallback versioning)" :coerce :long :default 1}
   :kind        {:desc "template kind: clojars | gitea (default: autodetect from origin)"
                 :coerce :keyword}
   :license     {:desc "override license name"}
   :license-url {:desc "override license url"}
   :force       {:desc "overwrite existing version.edn / workflow" :coerce :boolean}
   :help        {:desc "show this help" :coerce :boolean}})

(defn- detect-kind
  "clojars when origin is a github remote, else gitea. Defaults to clojars when
  there is no origin (a not-yet-remoted repo publishing to Clojars is the common
  case)."
  [target]
  (let [{:keys [out exit]} (p/sh {:dir (str target) :continue true}
                                 "git" "remote" "get-url" "origin")]
    (if (and (zero? exit) (str/includes? out "github.com")) :clojars
        (if (zero? exit) :gitea :clojars))))

(defn- usage []
  (str "bb-build — scaffold a hive project's build setup (lein-new style)\n\n"
       "  bb-build new <lib-short-name> <target-dir> [opts]\n\n"
       "Options:\n" (cli/format-opts {:spec spec}) "\n\n"
       "Kinds:\n"
       "  clojars  public GitHub repo -> Clojars (MIT).   Workflow: .github/workflows/release.yml\n"
       "  gitea    private Gitea repo -> Gitea Maven.      Workflow: .gitea/workflows/release.yml\n"))

(defn- cmd-new [{:keys [args opts]}]
  (let [[lib target] args]
    (when (or (str/blank? lib) (str/blank? target))
      (println "error: need <lib-short-name> and <target-dir>\n")
      (println (usage))
      (System/exit 2))
    (when-not (fs/exists? (fs/path target "deps.edn"))
      (println (str "error: no deps.edn in " target " — scaffold a Clojure project first"))
      (System/exit 1))
    (let [deps-text (slurp (str (fs/path target "deps.edn")))]
      (when-not (scaffold/publishable? deps-text)
        (println (str "REFUSED: " target " has :git or :local/root RUNTIME deps — "
                      "not publishable as-is.\nMove them into :test/:dev aliases first."))
        (System/exit 3))
      (let [kind (or (:kind opts) (detect-kind target))
            plan (scaffold/plan {:lib lib :target target :kind kind
                                 :minor (:minor opts)
                                 :license (:license opts) :license-url (:license-url opts)})
            results (scaffold/apply! plan (select-keys opts [:force]))]
        (println (str "Scaffolded " scaffold/group "/" lib " (" (name kind) ") into " target ":"))
        (doseq [{:keys [path status]} results]
          (println (format "  %-16s %s" (name status) path)))
        (println (str "\nAdd this alias to " target "/deps.edn under :aliases:\n"))
        (println (:build-alias plan))
        (println "Then verify locally (writes only to ~/.m2, no network):")
        (println (str "  (cd " target " && clojure -T:build install)"))))))

(defn -main [& argv]
  (let [{:keys [args opts]} (cli/parse-args argv {:spec spec})
        [command & rest-args] args]
    (cond
      (or (:help opts) (nil? command) (= command "help"))
      (println (usage))

      (= command "new")
      (cmd-new {:args rest-args :opts opts})

      :else
      (do (println (str "unknown command: " command "\n"))
          (println (usage))
          (System/exit 2)))))
