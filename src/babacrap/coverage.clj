(ns babacrap.coverage
  (:require [clojure.java.io :as io]
            [cloverage.coverage :as cloverage]))

(defn cloverage-args [{:keys [src-paths test-paths ns-regex test-ns-regex output]}]
  (vec
   (concat
    ["--output" output
     "--no-html"
     "--raw"]
    (mapcat (fn [p] ["--src-ns-path" p]) src-paths)
    (mapcat (fn [p] ["--test-ns-path" p]) test-paths)
    (mapcat (fn [r] ["--ns-regex" r]) ns-regex)
    (mapcat (fn [r] ["--test-ns-regex" r]) test-ns-regex))))

(defn run-cloverage! [opts]
  (let [args (cloverage-args opts)
        exit-code (binding [cloverage/*exit-after-test* false]
                    (apply cloverage/-main args))]
    (when-not (zero? exit-code)
      (throw (ex-info "Cloverage failed" {:exit-code exit-code
                                           :args args})))
    (io/file (:output opts) "raw-stats.clj")))

(defn read-raw-stats [raw-stats-file]
  ;; Cloverage raw stats are printed with clojure.pprint, not as strict EDN.
  ;; Source forms can contain reader literals like regexes, so use the Clojure
  ;; reader with eval disabled instead of clojure.edn/read-string.
  (binding [*read-eval* false]
    (read-string (slurp raw-stats-file))))

(defn covered-form? [form]
  (and (:tracked form)
       (:covered form)))

(defn tracked-form? [form]
  (:tracked form))

(defn file-matches? [coverage-file complexity-resource-file complexity-filename]
  (or (= coverage-file complexity-resource-file)
      (= coverage-file complexity-filename)
      (.endsWith ^String complexity-filename coverage-file)))

(defn in-range? [{:keys [row end-row]} {:keys [line]}]
  (and line
       (<= row line)
       (<= line end-row)))

(defn function-coverage [raw-stats {:keys [resource-file filename] :as function}]
  (let [forms (filter #(and (file-matches? (:file %) resource-file filename)
                            (in-range? function %)
                            (tracked-form? %))
                      raw-stats)
        total (count forms)
        covered (count (filter covered-form? forms))]
    {:tracked-forms total
     :covered-forms covered
     :coverage (if (pos? total)
                 (/ covered total)
                 0.0)}))
