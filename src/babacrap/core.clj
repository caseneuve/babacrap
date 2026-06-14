(ns babacrap.core
  (:gen-class)
  (:require [babacrap.complexity :as complexity]
            [babacrap.coverage :as coverage]
            [babacrap.util :as util]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

;; -- Pure --

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
  (util/merge-with-defaults default-options opts))

(defn crap-score [complexity coverage]
  ;; CRAP(m) = comp(m)^2 * (1 - cov(m))^3 + comp(m)
  ;; coverage is a ratio from 0.0 to 1.0.
  (+ complexity
     (* complexity complexity
        (Math/pow (- 1.0 (double coverage)) 3))))

(defn add-crap [function]
  (let [score (crap-score (:complexity function) (:coverage function))]
    (assoc function :crap score)))

(defn pct [ratio]
  (format "%.1f%%" (* 100.0 (double ratio))))

(defn format-score [n]
  (format "%.2f" (double n)))

(defn result-row
  [{:keys [filename row complexity coverage tracked-forms covered-forms crap]
    :as result}]
  [(format-score crap)
   (str complexity)
   (format "%s (%s/%s)" (pct coverage) covered-forms tracked-forms)
   (format "%s:%s %s" filename row (util/function-label result))])

(def ^:private result-columns
  [{:header "CRAP" :align :right}
   {:header "COMPLEX" :align :right}
   {:header "COVERAGE" :align :right}
   {:header "LOCATION" :align :left}])

(defn header-line [{:keys [results failures threshold]}]
  (if (empty? results)
    "CRAP analysis: PASS — no functions found."
    (let [status (if (seq failures) "FAIL" "PASS")]
      (format "CRAP analysis: %s — %s/%s over threshold %s"
              status (count failures) (count results) (format-score threshold)))))

(defn format-text [{:keys [results] :as report-data}]
  (util/format-table-report (header-line report-data)
                            result-columns
                            result-row
                            results))

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
    summary
    ""
    "Examples:"
    "  bb crap --src src --test test --ns-regex 'my-app.*'"
    "  bb crap --no-coverage --src src --format edn"
    "  babacrap crap --src src --test test"]))

(defn error-text [errors summary]
  (str/join
   \newline
   (concat errors ["" (usage summary)])))

(defn render-report [report-data format]
  (util/render-report format-text report-data format))

;; -- Side effects --

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

(defn run-result [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        options (merge-defaults options)]
    (cond
      (or (:help options) (empty? args))
      {:exit 0
       :out (usage summary)}

      (seq errors)
      {:exit 2
       :err (error-text errors summary)}

      :else
      (let [report-data (report (:crap-threshold options)
                                (analyze options))]
        {:exit (exit-code report-data)
         :out (render-report report-data (:format options))}))))

(defn emit-result [result]
  (util/emit-result result))

(defn run [args]
  (let [{:keys [exit] :as result} (run-result args)]
    (util/emit-result result)
    exit))

;; -- CLI entry point --

(defn -main [& args]
  (util/exit-nonzero! (run args)))
