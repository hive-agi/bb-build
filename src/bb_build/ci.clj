(ns bb-build.ci
  "Render the managed CI workflow templates into a repo, and report drift.

  The templates in resources/bb_build/templates are the only place a CI action
  version is written. `sync-task` renders them into a repo's workflow directory
  (a dry run prints a diff and writes nothing unless --write); `drift-task`
  fails when a managed copy differs from its template, or when any workflow in
  the repo pins an action at a major below the template's.

  `render`, `action-majors`, `floors-for`, `drift-findings` and `unified-diff`
  are pure functions over strings. Only the two task fns read and write files."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ── what a forge looks like on disk ─────────────────────────────────────────

(def forges
  "Per forge: the workflow directory a repo uses, and the template resource
  rendered under each managed file name. release.yml is a different template on
  each forge; staging-gate.yml is one file for both."
  {:github {:dir       ".github/workflows"
            :templates {"staging-gate.yml" "bb_build/templates/staging-gate.yml"
                        "release.yml"      "bb_build/templates/release-clojars.yml"}}
   :gitea  {:dir       ".gitea/workflows"
            :templates {"staging-gate.yml" "bb_build/templates/staging-gate.yml"
                        "release.yml"      "bb_build/templates/release-gitea.yml"}}})

(def action-floors
  "The lowest action major a hive workflow may pin, by action. The templates
  pin every one of these; the entries exist so drift also covers an action a
  hand-written workflow uses and no template does."
  {"actions/checkout"            7
   "actions/setup-java"          6
   "actions/cache"               6
   "actions/setup-node"          7
   "actions/upload-artifact"     7
   "softprops/action-gh-release" 3
   "DeLaGuardo/setup-clojure"    13})

;; ── pure: rendering ─────────────────────────────────────────────────────────

(defn- strip-trailing-newlines [s]
  (loop [s s]
    (if (str/ends-with? s "\n")
      (recur (subs s 0 (dec (count s))))
      s)))

(defn render
  "The bytes a managed workflow is written with, given its template text. Pure.
  A template carries no per-repo substitution, so this only normalises line
  endings and the trailing newline: every repo gets a byte-identical copy."
  [template-text]
  (-> template-text
      (str/replace "\r\n" "\n")
      (str/replace "\r" "\n")
      strip-trailing-newlines
      (str "\n")))

(defn sha256
  "Lower-case hex SHA-256 of a string's UTF-8 bytes. Pure."
  [s]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %)
                    (.digest d (.getBytes ^String s "UTF-8"))))))

;; ── pure: parsing the action pins out of a workflow ─────────────────────────

(defn- split-once
  "Split `s` at its first `delim` (a one-character string). Pure. [before
  after], or nil when `delim` is absent."
  [s delim]
  (when-let [i (str/index-of s delim)]
    [(subs s 0 i) (subs s (inc i))]))

(defn- split-spaces
  "`s` split on every space, with tabs read as spaces. Pure. No regex, and no
  clojure.string/split, whose second argument must be a Pattern."
  [s]
  (loop [s (str/replace (or s "") "\t" " ") parts [] cur ""]
    (if-let [c (first s)]
      (if (= c \space)
        (recur (subs s 1) (if (seq cur) (conj parts cur) parts) "")
        (recur (subs s 1) parts (str cur c)))
      (if (seq cur) (conj parts cur) parts))))

(defn- tokens
  "The non-blank tokens of `s`. Pure."
  [s]
  (remove str/blank? (split-spaces s)))

(defn- uses-value
  "The value of a `uses:` line (`- uses: x` or `uses: x`), trimmed, or nil."
  [line]
  (let [t (str/trim line)]
    (cond
      (str/starts-with? t "- uses:") (str/trim (subs t 7))
      (str/starts-with? t "uses:")   (str/trim (subs t 5))
      :else nil)))

(defn- major-of
  "The major number of a version ref: v7, v7.0.1 and 13.7.0 give 7, 7 and 13.
  nil for a ref with no leading number (a SHA, a docker ref, a local path).
  Pure."
  [ref]
  (let [head   (first (split-once ref ".")) ; nil when ref has no dot
        head   (or head ref)
        digits (if (str/starts-with? (str/lower-case head) "v")
                 (subs head 1)
                 head)]
    (and (seq digits) (parse-long digits))))

(defn action-ref
  "Parse one `uses:` value into {:action \"owner/name\" :major n}, or nil when it
  is not a pinned owner/name action. Pure: splits on whitespace, @ and . only."
  [value]
  (when (and (seq value) (not (str/includes? value "://")))
    (when-let [[action ref] (split-once (first (tokens value)) "@")]
      (let [major (and ref (major-of ref))]
        (when (and major (str/includes? action "/")
                   (not (str/starts-with? action ".")))
          {:action action :major major})))))

(defn action-majors
  "Map of action to the LOWEST major it is pinned at anywhere in
  `workflow-text`. Pure. A file pinning checkout at both v7 and v4 reports 4,
  because the lower pin is the one that needs raising."
  [workflow-text]
  (reduce (fn [acc line]
            (if-let [{:keys [action major]} (action-ref (uses-value line))]
              (update acc action (fn [old] (if old (min old major) major)))
              acc))
          {}
          (str/split-lines (or workflow-text ""))))

;; ── pure: drift ─────────────────────────────────────────────────────────────

(defn floors-for
  "The action floors in force: `action-floors`, raised by whatever the given
  template texts pin. Pure."
  [template-texts]
  (reduce (fn [acc text]
            (reduce (fn [acc [action major]]
                      (update acc action (fn [old] (if old (max old major) major))))
                    acc
                    (action-majors text)))
          action-floors
          template-texts))

(defn drift-findings
  "Pure. `expected` maps a managed workflow file name to its rendered text;
  `actual` maps every workflow file name present in the repo to its text (an
  absent managed file has no entry). One finding per problem:
  {:kind :missing|:differs :path name} for a managed copy, and
  {:kind :major :path name :action a :found n :wanted n} for any workflow
  pinning an action below its floor."
  [expected actual]
  (let [floors (floors-for (vals expected))]
    (vec
     (concat
      (for [[name text] expected
            :let  [have (get actual name)]
            :when (not= (sha256 text) (some-> have sha256))]
        (if have
          {:kind :differs :path name}
          {:kind :missing :path name}))
      (for [[name text] actual
            [action major] (action-majors text)
            :let  [wanted (get floors action)]
            :when (and wanted (< major wanted))]
        {:kind :major :path name :action action :found major :wanted wanted})))))

(defn finding-line
  "One printable line for a finding. Pure."
  [{:keys [kind path action found wanted]}]
  (case kind
    :missing (str path ": missing (no copy of the template)")
    :differs (str path ": differs from its template")
    :major   (str path ": " action " is pinned at v" found
                  ", the template pins v" wanted)))

;; ── pure: unified diff ─────────────────────────────────────────────────────

(defn- lcs-table
  "Longest-common-subsequence lengths for two line vectors, keyed [i j] over
  the 1-based prefixes."
  [a b]
  (reduce (fn [tbl i]
            (reduce (fn [tbl j]
                      (assoc! tbl [i j]
                              (if (= (nth a (dec i)) (nth b (dec j)))
                                (inc (get tbl [(dec i) (dec j)] 0))
                                (max (get tbl [(dec i) j] 0)
                                     (get tbl [i (dec j)] 0)))))
                    tbl
                    (range 1 (inc (count b)))))
          (transient {})
          (range 1 (inc (count a)))))

(defn- diff-ops
  "[:eq line] / [:del line] / [:add line] ops turning `a` into `b`. Pure."
  [a b]
  (let [tbl (persistent! (lcs-table a b))]
    (loop [i (count a) j (count b) acc ()]
      (cond
        (and (zero? i) (zero? j)) (vec acc)
        (zero? i)                 (recur i (dec j) (cons [:add (nth b (dec j))] acc))
        (zero? j)                 (recur (dec i) j (cons [:del (nth a (dec i))] acc))
        (= (nth a (dec i)) (nth b (dec j)))
        (recur (dec i) (dec j) (cons [:eq (nth a (dec i))] acc))
        (>= (get tbl [(dec i) j] 0) (get tbl [i (dec j)] 0))
        (recur (dec i) j (cons [:del (nth a (dec i))] acc))
        :else (recur i (dec j) (cons [:add (nth b (dec j))] acc))))))

(defn- changed? [op] (not= :eq (first op)))

(defn- hunk-ranges
  "[{:start i :end n} ...] windows of `context` lines around each change,
  merging windows that touch. Pure."
  [ops context]
  (let [n (count ops)]
    (reduce (fn [ranges i]
              (let [start  (max 0 (- i context))
                    end    (min n (+ i context 1))
                    last-r (peek ranges)]
                (if (and last-r (<= start (:end last-r)))
                  (conj (pop ranges) (assoc last-r :end (max (:end last-r) end)))
                  (conj ranges {:start start :end end}))))
            []
            (keep-indexed (fn [i op] (when (changed? op) i)) ops))))

(defn- line-nums
  "The [old new] line numbers a hunk starting at index `upto` begins on. Pure."
  [ops upto]
  (reduce (fn [{:keys [o n]} op]
            (case (first op)
              :eq  {:o (inc o) :n (inc n)}
              :del {:o (inc o) :n n}
              :add {:o o :n (inc n)}))
          {:o 1 :n 1}
          (subvec ops 0 upto)))

(defn unified-diff
  "A unified diff of `old-text` to `new-text` at `path`, or \"\" when they are
  equal. Pure."
  [path old-text new-text]
  (if (= old-text new-text)
    ""
    (let [ops (diff-ops (str/split-lines old-text) (str/split-lines new-text))]
      (str "--- a/" path "\n"
           "+++ b/" path "\n"
           (apply str
                  (for [{:keys [start end]} (hunk-ranges ops 3)
                        :let [body (subvec ops start end)
                              {o :o n :n} (line-nums ops start)
                              oc (count (filter #(not= :add (first %)) body))
                              nc (count (filter #(not= :del (first %)) body))]]
                    (str "@@ -" o "," oc " +" n "," nc " @@\n"
                         (apply str
                                (for [[op line] body]
                                  (str (case op :eq " " :del "-" :add "+")
                                       line "\n"))))))))))

;; ── templates off the classpath ─────────────────────────────────────────

(defn- read-resource [path]
  (if-let [r (io/resource path)]
    (slurp r)
    (throw (ex-info (str "template not found on classpath: " path) {:path path}))))

(defn rendered-templates
  "Managed file name -> rendered text, for `forge`. Reads the classpath
  templates; `render` itself is pure."
  [forge]
  (let [spec (or (get forges forge)
                 (throw (ex-info (str "unknown forge: " forge)
                                 {:forge forge :known (vec (keys forges))})))]
    (into {}
          (for [[name resource] (:templates spec)]
            [name (render (read-resource resource))]))))

;; ── the thin boundary: filesystem only below here ─────────────────────────

(defn detect-forge
  "The forge a repo's workflow directory shows: gitea when it has
  .gitea/workflows and no .github/workflows, else github."
  [repo]
  (if (and (fs/directory? (fs/path repo ".gitea" "workflows"))
           (not (fs/directory? (fs/path repo ".github" "workflows"))))
    :gitea
    :github))

(defn workflow-dir [repo forge] (fs/path repo (get-in forges [forge :dir])))

(defn- workflow-file? [f]
  (let [n (str (fs/file-name f))]
    (and (fs/regular-file? f)
         (or (str/ends-with? n ".yml") (str/ends-with? n ".yaml")))))

(defn read-workflows
  "File name -> text for every workflow file in the repo's workflow directory
  ({} when it has none)."
  [repo forge]
  (let [dir (workflow-dir repo forge)]
    (if (fs/directory? dir)
      (into {} (for [f (fs/list-dir dir) :when (workflow-file? f)]
                 [(str (fs/file-name f)) (slurp (str f))]))
      {})))

(def ^:private cli-spec
  {:forge {:desc "forge: github | gitea (default: detected from the repo)" :coerce :keyword}
   :write {:desc "write the rendered files (default: print a diff only)" :coerce :boolean}
   :help  {:desc "show this help" :coerce :boolean}})

(defn- parse-target [argv]
  (let [{:keys [args opts]} (cli/parse-args (vec argv) {:spec cli-spec})]
    (assoc opts :repo (first args))))

(defn- ok-dir? [repo]
  (if (fs/directory? repo)
    true
    (do (println (str "error: not a directory: " repo))
        (System/exit 2))))

(defn sync-task
  "ci:sync <repo-dir> [--forge github|gitea] [--write]: render each managed
  template into the repo's workflow directory, printing a unified diff. Writes
  only with --write, and only managed files."
  [argv]
  (let [{:keys [repo write help]} (parse-target argv)]
    (when (or help (nil? repo))
      (println "usage: bb ci:sync <repo-dir> [--forge github|gitea] [--write]")
      (System/exit (if (nil? repo) 2 0)))
    (ok-dir? repo)
    (let [forge    (or (:forge (parse-target argv)) (detect-forge repo))
          dir      (workflow-dir repo forge)
          expected (rendered-templates forge)
          findings (drift-findings expected (read-workflows repo forge))
          copies   (filterv #(#{:missing :differs} (:kind %)) findings)]
      (if (empty? copies)
        (println (str "ci:sync: " repo " (" (name forge) "): all " (count expected)
                      " managed workflows already match their templates"))
        (let [diffs (into (sorted-map)
                          (for [{:keys [path kind]} copies]
                            [path (unified-diff path
                                                (if (= :missing kind) "" (get (read-workflows repo forge) path ""))
                                                (get expected path))]))]
          (doseq [[_ diff] diffs] (print diff))
          (if write
            (do (fs/create-dirs dir)
                (doseq [[path _] diffs]
                  (spit (str (fs/path dir path)) (get expected path))
                  (println (str "ci:sync: wrote " (fs/path dir path)))))
            (println (str "ci:sync: " (count diffs)
                          " file(s) would change; re-run with --write to apply"))))))))

(defn drift-task
  "ci:drift <repo-dir> [--forge github|gitea]: print one line per finding and
  exit non-zero when the repo has drifted from the templates."
  [argv]
  (let [{:keys [repo help forge]} (parse-target argv)]
    (when (or help (nil? repo))
      (println "usage: bb ci:drift <repo-dir> [--forge github|gitea]")
      (System/exit (if (nil? repo) 2 0)))
    (ok-dir? repo)
    (let [f (or forge (detect-forge repo))
          findings (drift-findings (rendered-templates f) (read-workflows repo f))]
      (if (empty? findings)
        (println (str "ci:drift: " repo " (" (name f) "): clean"))
        (do (doseq [x findings] (println (finding-line x)))
            (System/exit 1))))))
