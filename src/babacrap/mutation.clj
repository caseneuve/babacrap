(ns babacrap.mutation
  (:gen-class)
  (:require [babacrap.complexity :as complexity]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p])
  (:import [java.util.concurrent TimeUnit]))

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
   ["-h" "--help"]])


(def token-replacements
  {'true "false"
   'false "true"
   '= "not="
   'not= "="
   '< "<="
   '<= "<"
   '> ">="
   '>= ">"
   '+ "-"
   '- "+"
   '* "/"
   '/ "*"
   'inc "dec"
   'dec "inc"
   'and "or"
   'or "and"
   'if "if-not"
   'if-not "if"
   'when "when-not"
   'when-not "when"
   'pos? "neg?"
   'neg? "pos?"
   'zero? "pos?"})

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
  ;; Replacement values are raw source text inserted at the token's range.
  (let [value (complexity/token-value node)]
    (when-let [replacement (get token-replacements value)]
      [(mutation source line-starts filename functions node :replace-token replacement)])))

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

(defn collect-file-mutants [filename]
  (let [source (slurp filename)
        line-starts (line-start-offsets source)
        forms-node (p/parse-file-all filename)
        functions (vec (complexity/analyze-file filename))]
    (->> (collect* source line-starts filename functions forms-node)
         (remove nil?)
         (filter :function))))

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

(defn wait-for-process [proc timeout-ms]
  (let [^Process p (:proc proc)]
    (if (.waitFor p timeout-ms TimeUnit/MILLISECONDS)
      @proc
      (do
        (process/destroy-tree proc)
        (assoc @proc :timeout? true :exit ::timeout)))))

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

(defn print-text [results]
  (let [{:keys [total killed survived timeout mutation-score]} (summarize results)]
    (println "Mutation analysis")
    (println "-----------------")
    (println (format "mutants: %s, killed: %s, survived: %s, timeout: %s, score: %.1f%%"
                     total killed survived timeout mutation-score))
    (when (seq results)
      (println)
      (doseq [{:keys [id filename row col mutator original replacement status function]} results]
        (println (format "#%s %s %s:%s:%s %s" id (name status) filename row col (format-function function)))
        (println (format "  %s: %s => %s" (name mutator) (pr-str original) (pr-str replacement)))))))

(defn merge-defaults [opts]
  (merge default-options
         (into {} (remove (fn [[_ v]] (= [] v)) opts))))

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

(defn run [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        options (merge-defaults options)]
    (cond
      (:help options)
      (do (println (usage summary)) 0)

      (seq errors)
      (do (binding [*out* *err*]
            (doseq [e errors] (println e))
            (println)
            (println (usage summary)))
          2)

      :else
      (let [results (run-mutation-analysis options)
            summary (summarize results)]
        (case (:format options)
          :edn (pprint/pprint {:summary summary :results results})
          :text (print-text results))
        (if (pos? (:survived summary)) 1 0)))))

(defn -main [& args]
  (let [exit-code (run args)]
    (when-not (zero? exit-code)
      (System/exit exit-code))))
