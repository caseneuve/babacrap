(ns babacrap.mutation
  (:gen-class)
  (:require [babacrap.complexity :as complexity]
            [babacrap.table :as table]
            [babacrap.util :as util]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(def default-options
  {:src-paths ["src"]
   :test-command "bb test"
   :timeout-ms 10000
   :limit nil
   :format :text})

(def cli-options
  [["-p" "--src PATH" "Source directory; can be repeated"
    :id :src-paths
    :default []
    :assoc-fn (fn [m k v] (update m k conj v))]
   ["-c" "--test-command COMMAND" "Shell command that runs tests"
    :default (:test-command default-options)]
   [nil "--timeout-ms MS" "Timeout per mutant"
    :parse-fn #(Long/parseLong %)
    :default (:timeout-ms default-options)]
   [nil "--limit N" "Maximum number of mutants to run"
    :parse-fn #(Long/parseLong %)]
   [nil "--format FORMAT" "Output format: text or edn"
    :parse-fn keyword
    :validate [#{:text :edn} "Must be text or edn"]
    :default (:format default-options)]
   [nil "--force" "Run even if mutation target files have uncommitted changes"
    :default false]
   ["-h" "--help"]])


(def token-replacements
  ;; Keys are whatever `complexity/token-value` yields for the source token:
  ;; booleans for `true`/`false`, symbols for everything else. Values are
  ;; the *replacement value* — `render-replacement` turns them into source
  ;; text so the data stays free of textual conventions. Boolean replacements
  ;; are stored as booleans too (not symbols) because `'false` reads as the
  ;; literal `false` after quoting.
  {true false
   false true
   '= 'not=
   'not= '=
   '< '<=
   '<= '<
   '> '>=
   '>= '>
   '+ '-
   '- '+
   '* '/
   '/ '*
   'inc 'dec
   'dec 'inc
   'and 'or
   'or 'and
   'if 'if-not
   'if-not 'if
   'when 'when-not
   'when-not 'when
   'pos? 'neg?
   'neg? 'pos?
   'zero? 'pos?})

(defn render-replacement [value]
  (pr-str value))

(defn line-start-offsets [s]
  (loop [idx 0
         starts [0]]
    (let [newline (.indexOf ^String s "\n" idx)]
      (if (neg? newline)
        starts
        (recur (inc newline) (conj starts (inc newline)))))))

(defn position->offset [line-starts row col]
  (+ (nth line-starts (dec row))
     (dec col)))

(defn node-range [line-starts node]
  (let [{:keys [row col end-row end-col]} (meta node)]
    (when (and row col end-row end-col)
      {:row row
       :col col
       :end-row end-row
       :end-col end-col
       :start (position->offset line-starts row col)
       :end (position->offset line-starts end-row end-col)})))

(defn fragment [source line-starts node]
  (when-let [{:keys [start end]} (node-range line-starts node)]
    (subs source start end)))

(defn function-for-range [functions {:keys [row end-row]}]
  (some (fn [f]
          (when (and row end-row
                     (<= (:row f) row)
                     (<= end-row (:end-row f)))
            f))
        functions))

(defn mutation [source line-starts filename functions node mutator replacement]
  (let [{:keys [row col end-row end-col start end] :as r} (node-range line-starts node)
        original (when r (subs source start end))]
    (when (and r original (not= original replacement))
      (when-let [function (function-for-range functions r)]
        {:filename filename
         :row row
         :col col
         :end-row end-row
         :end-col end-col
         :start start
         :end end
         :mutator mutator
         :original original
         :replacement replacement
         :function (select-keys function [:ns :name :var :row :end-row :complexity])}))))

(declare collect*)

(defn collect-token-mutants [source line-starts filename functions node]
  (let [value (complexity/token-value node)]
    (when (contains? token-replacements value)
      [(mutation source line-starts filename functions node
                 :replace-token (render-replacement (get token-replacements value)))])))

(defn collect-not-mutants [source line-starts filename functions node]
  (when (and (= :list (n/tag node))
             (= 'not (some-> node complexity/children first complexity/token-value)))
    (when-let [arg (second (complexity/children node))]
      [(mutation source line-starts filename functions node :remove-not
                 (fragment source line-starts arg))])))

(defn collect-if-condition-mutants [source line-starts filename functions node]
  (when (and (= :list (n/tag node))
             (contains? '#{if if-not when when-not} (some-> node complexity/children first complexity/token-value)))
    (when-let [test-node (second (complexity/children node))]
      [(mutation source line-starts filename functions test-node :force-condition "true")
       (mutation source line-starts filename functions test-node :force-condition "false")])))

(defn collect* [source line-starts filename functions node]
  (if (= :quote (n/tag node))
    []
    (let [own-mutants (concat
                       (when (= :token (n/tag node))
                         (collect-token-mutants source line-starts filename functions node))
                       (collect-not-mutants source line-starts filename functions node)
                       (collect-if-condition-mutants source line-starts filename functions node))
          child-mutants (mapcat #(collect* source line-starts filename functions %)
                                (complexity/children node))]
      (concat own-mutants child-mutants))))

(defn with-ids [mutants]
  (map-indexed (fn [idx m] (assoc m :id (inc idx))) mutants))

(defn parse-file
  "IO: read `filename` once and return a map with its source text and
  parsed forms, ready to feed into pure analysis."
  [filename]
  {:filename filename
   :source (slurp filename)
   :forms (p/parse-file-all filename)})

(defn collect-file-mutants-from
  "Pure: given a parsed file and a function inventory, return the valid
  mutants for that file."
  [{:keys [filename source forms]} functions]
  (let [line-starts (line-start-offsets source)]
    (->> (collect* source line-starts filename functions forms)
         (remove nil?)
         (filter :function))))

(defn collect-file-mutants [filename]
  (let [parsed (parse-file filename)
        functions (vec (complexity/analyze-forms (:forms parsed) filename))]
    (collect-file-mutants-from parsed functions)))

(defn collect-mutants [src-paths]
  (->> (complexity/source-files src-paths)
       (mapcat collect-file-mutants)
       with-ids
       vec))

(def backup-suffix ".babacrap.bak")

(defn backup-path [filename]
  (str filename backup-suffix))

(defn restore-backup! [filename]
  (let [backup (backup-path filename)]
    (when (fs/exists? backup)
      (spit filename (slurp backup))
      (fs/delete-if-exists backup))))

(defn restore-backups! [src-paths]
  (doseq [filename (complexity/source-files src-paths)]
    (restore-backup! filename)))

(defn apply-mutant [source {:keys [start end replacement]}]
  (str (subs source 0 start)
       replacement
       (subs source end)))

(def timeout-marker ::timeout)

(defn wait-for-process [proc timeout-ms]
  (let [result (deref proc timeout-ms timeout-marker)]
    (if (= timeout-marker result)
      (do
        (process/destroy-tree proc)
        (assoc @proc :timeout? true :exit timeout-marker))
      result)))

(defn git-status
  "Classify `filename` via git. Returns :clean, :dirty, or :unknown.
  :unknown covers the cases where git is missing, the path is outside a
  repo, or git itself errored — nothing we can corrupt, so callers are
  free to proceed."
  [filename]
  (let [path (fs/absolutize filename)
        dir (or (fs/parent path) (fs/path "."))
        {:keys [exit out]}
        (process/sh {:dir (str dir)
                     :out :string :err :string :continue true}
                    "git" "status" "--porcelain" "--" (str (fs/file-name path)))]
    (cond
      (not (zero? exit)) :unknown
      (seq (str/trim (or out ""))) :dirty
      :else :clean)))

(defn dirty-targets [filenames]
  ;; Policy: treat :unknown as not-dirty — nothing to corrupt if there's no repo.
  (vec (filter #(= :dirty (git-status %)) filenames)))

(defn run-test-command [{:keys [test-command timeout-ms]}]
  (let [proc (process/process {:out :string
                               :err :string
                               :shutdown process/destroy-tree}
                              "bash" "-lc" test-command)]
    (wait-for-process proc timeout-ms)))

(defn run-mutant! [opts mutant]
  (let [filename (:filename mutant)
        backup (backup-path filename)
        original-source (slurp filename)
        mutated-source (apply-mutant original-source mutant)]
    (try
      (spit backup original-source)
      (spit filename mutated-source)
      (let [{:keys [exit timeout? out err]} (run-test-command opts)
            status (cond
                     timeout? :timeout
                     (zero? exit) :survived
                     :else :killed)]
        (assoc mutant
               :status status
               :exit exit
               :out out
               :err err))
      (finally
        (spit filename original-source)
        (fs/delete-if-exists backup)))))

(defn run-mutation-analysis [opts]
  (restore-backups! (:src-paths opts))
  (let [mutants (cond->> (collect-mutants (:src-paths opts))
                  (:limit opts) (take (:limit opts)))]
    (vec (map #(run-mutant! opts %) mutants))))

(defn summarize [results]
  (let [freqs (frequencies (map :status results))
        total (count results)
        killed (get freqs :killed 0)
        timed-out (get freqs :timeout 0)
        survived (get freqs :survived 0)]
    {:total total
     :killed killed
     :survived survived
     :timeout timed-out
     :mutation-score (if (pos? total)
                       (* 100.0 (/ (+ killed timed-out) total))
                       0.0)}))

(defn format-function [{:keys [var]}]
  (or (some-> var str) "<unknown>"))

(defn report [results]
  {:results results
   :summary (summarize results)})

(defn header-line [{:keys [summary]}]
  (let [{:keys [total killed survived timeout mutation-score]} summary]
    (if (zero? total)
      "Mutation analysis: PASS — no mutants generated."
      (let [status (if (pos? survived) "FAIL" "PASS")]
        (format "Mutation analysis: %s — %s killed, %s survived, %s timeout of %s (score %.1f%%)"
                status killed survived timeout total mutation-score)))))

(defn mutant-row
  [{:keys [id filename row col mutator original replacement status function]}]
  [(str "#" id)
   (name status)
   (format "%s:%s:%s %s" filename row col (format-function function))
   (format "%s: %s => %s" (name mutator) (pr-str original) (pr-str replacement))])

(def ^:private mutant-columns
  [{:header "ID" :align :right}
   {:header "STATUS" :align :left}
   {:header "LOCATION" :align :left}
   {:header "MUTATION" :align :left}])

(defn format-text [{:keys [results] :as report-data}]
  (let [header (header-line report-data)]
    (str \newline
         (if (empty? results)
           header
           (str header
                \newline
                (table/render mutant-columns (map mutant-row results)))))))

(defn merge-defaults [opts]
  (util/merge-with-defaults default-options opts))

(defn usage [summary]
  (str/join
   \newline
   ["babacrap mutation: simple mutation testing for babashka/Clojure projects"
    ""
    "WARNING: mutates files in place while each mutant runs, then restores them."
    "Do not interrupt the process. Use git to verify your worktree afterwards."
    ""
    "Usage: bb -m babacrap.mutation [options]"
    ""
    "Options:"
    summary]))

(defn error-text [errors summary]
  (str/join \newline (concat errors ["" (usage summary)])))

(defn dirty-targets-text [dirty]
  (str/join
   \newline
   (concat ["Mutation targets have uncommitted changes:"]
           (map #(str "  " %) dirty)
           ["" "Commit or stash them, or re-run with --force."])))

(defn render-edn [x]
  (str/trimr (with-out-str (pprint/pprint x))))

(defn render-report [report-data format]
  (case format
    :edn (render-edn report-data)
    :text (format-text report-data)))

(defn mutation-exit-code [{:keys [summary]}]
  (if (pos? (:survived summary)) 1 0))

(defn run-result [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        options (merge-defaults options)]
    (cond
      (:help options)
      {:exit 0
       :out (usage summary)}

      (seq errors)
      {:exit 2
       :err (error-text errors summary)}

      :else
      (do
        ;; Restore any leftover backups before parsing, so a SIGKILLed prior
        ;; run does not feed a half-mutated file into `collect-mutants`.
        (restore-backups! (:src-paths options))
        (let [targets (distinct (map :filename (collect-mutants (:src-paths options))))
              dirty (when-not (:force options) (dirty-targets targets))]
          (if (seq dirty)
            {:exit 3
             :err (dirty-targets-text dirty)}
            (let [report-data (report (run-mutation-analysis options))]
              {:exit (mutation-exit-code report-data)
               :out (render-report report-data (:format options))})))))))

(defn emit-result [{:keys [out err]}]
  (when err
    (binding [*out* *err*]
      (println err)))
  (when out
    (println out)))

(defn run [args]
  (let [{:keys [exit] :as result} (run-result args)]
    (emit-result result)
    exit))

(defn -main [& args]
  (let [exit-code (run args)]
    (when-not (zero? exit-code)
      (System/exit exit-code))))
