(ns babacrap.core
  (:gen-class)
  (:require [babacrap.complexity :as complexity]
            [babacrap.coverage :as coverage]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

(def default-options
  {:src-paths ["src"]
   :test-paths ["test"]
   :ns-regex [".*"]
   :test-ns-regex [".*-test"]
   :output "target/babacrap/coverage"
   :format :text
   :crap-threshold 30.0})

(def cli-options
  [["-p" "--src PATH" "Source directory; can be repeated"
    :id :src-paths
    :default []
    :assoc-fn (fn [m k v] (update m k conj v))]
   ["-s" "--test PATH" "Test directory; can be repeated"
    :id :test-paths
    :default []
    :assoc-fn (fn [m k v] (update m k conj v))]
   ["-n" "--ns-regex REGEX" "Namespace regex to instrument; can be repeated"
    :id :ns-regex
    :default []
    :assoc-fn (fn [m k v] (update m k conj v))]
   ["-t" "--test-ns-regex REGEX" "Test namespace regex; can be repeated"
    :id :test-ns-regex
    :default []
    :assoc-fn (fn [m k v] (update m k conj v))]
   ["-o" "--output DIR" "Cloverage output directory"]
   [nil "--format FORMAT" "Output format: text or edn"
    :parse-fn keyword
    :validate [#{:text :edn} "Must be text or edn"]]
   [nil "--crap-threshold N" "Exit non-zero when any CRAP score is over N"
    :parse-fn #(Double/parseDouble %)]
   [nil "--[no-]coverage" "Run Cloverage before scoring"
    :id :coverage?
    :default true]
   ["-h" "--help"]])

(defn merge-defaults [opts]
  (merge default-options
         (into {} (remove (fn [[_ v]] (= [] v)) opts))))

(defn crap-score [complexity coverage]
  ;; CRAP(m) = comp(m)^2 * (1 - cov(m))^3 + comp(m)
  ;; coverage is a ratio from 0.0 to 1.0.
  (+ complexity
     (* complexity complexity
        (Math/pow (- 1.0 (double coverage)) 3))))

(defn add-crap [function]
  (let [score (crap-score (:complexity function) (:coverage function))]
    (assoc function :crap score)))

(defn analyze [{:keys [src-paths coverage?] :as opts}]
  (let [functions (vec (complexity/analyze-paths src-paths))
        raw-stats (if coverage?
                    (-> (coverage/run-cloverage! opts)
                        coverage/read-raw-stats)
                    [])]
    (->> functions
         (map (fn [function]
                (merge function
                       (coverage/function-coverage raw-stats function))))
         (map add-crap)
         (sort-by (juxt (comp - :crap) :filename :row))
         vec)))

(defn pct [ratio]
  (format "%.1f%%" (* 100.0 (double ratio))))

(defn format-score [n]
  (format "%.2f" (double n)))

(defn function-label [{:keys [var arity-index]}]
  (str var (when (pos? arity-index)
             (str "#" arity-index))))

(defn result-lines
  [{:keys [filename row complexity coverage tracked-forms covered-forms crap]
    :as result}]
  [(format "%s:%s %s" filename row (function-label result))
   (format "  complexity: %s" complexity)
   (format "  coverage:   %s (%s/%s forms)"
           (pct coverage) covered-forms tracked-forms)
   (format "  CRAP:       %s" (format-score crap))])

(defn format-text [results]
  (str/join
   \newline
   (if (seq results)
     (concat ["CRAP analysis" "-------------"]
             (mapcat result-lines results))
     ["No functions found."])))

(defn print-text [results]
  (println (format-text results)))

(defn over-threshold [threshold results]
  (filter #(> (:crap %) threshold) results))

(defn report [threshold results]
  {:results results
   :failures (vec (over-threshold threshold results))
   :threshold threshold})

(defn exit-code [{:keys [failures]}]
  (if (seq failures) 1 0))

(defn usage [summary]
  (str/join
   \newline
   ["babacrap: CRAP analysis for babashka/Clojure projects"
    ""
    "Usage: bb -m babacrap.core [options]"
    ""
    "Options:"
    summary]))

(defn error-text [errors summary]
  (str/join
   \newline
   (concat errors ["" (usage summary)])))

(defn print-report [{:keys [results] :as report-data} format]
  (case format
    :edn (pprint/pprint report-data)
    :text (print-text results)))

(defn run [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        options (merge-defaults options)]
    (cond
      (:help options)
      (do (println (usage summary)) 0)

      (seq errors)
      (do (binding [*out* *err*]
            (println (error-text errors summary)))
          2)

      :else
      (let [report-data (report (:crap-threshold options)
                                (analyze options))]
        (print-report report-data (:format options))
        (exit-code report-data)))))

(defn -main [& args]
  (let [exit-code (run args)]
    (when-not (zero? exit-code)
      (System/exit exit-code))))
