(ns babacrap.util
  (:require [babacrap.table :as table]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn merge-with-defaults
  "Merge `opts` over `defaults`, dropping keys in `opts` whose value is an
  empty vector. Repeatable CLI flags default to `[]` — callers want the
  real defaults back when nothing was passed."
  [defaults opts]
  (merge defaults (into {} (remove (fn [[_ v]] (= [] v)) opts))))

(defmacro with-captured-err
  "Run `body` with *err* bound to a fresh writer, then return the captured
  string. Mirrors `with-out-str` for the error stream."
  [& body]
  `(with-out-str
     (binding [*err* *out*]
       ~@body)))

(defn render-edn [x]
  (str/trimr (with-out-str (pprint/pprint x))))

(defn render-report [format-text report-data format]
  (case format
    :edn (render-edn report-data)
    :text (format-text report-data)))

(defn format-table-report [header columns row-fn results]
  (str \newline
       (if (empty? results)
         header
         (str header
              \newline
              (table/render columns (map row-fn results))))))

(defn function-label [{:keys [var arity-index]}]
  (str var (when (pos? arity-index)
             (str "#" arity-index))))

(defn emit-result [{:keys [out err]}]
  (when err
    (binding [*out* *err*]
      (println err)))
  (when out
    (println out)))

(defn exit-nonzero! [exit-code]
  (when-not (zero? exit-code)
    (System/exit exit-code)))
