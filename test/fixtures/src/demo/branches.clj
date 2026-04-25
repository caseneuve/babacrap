(ns demo.branches)

(defn branch-sampler [x xs]
  (let [acc (cond-> {:x x}
              (pos? x) (assoc :positive true)
              (zero? x) (assoc :zero true))
        label (condp = x
                0 :zero
                1 :one
                :many)
        cased (case label
                :zero 0
                :one 1
                2)
        tried (try
                (/ 10 x)
                (catch Exception _
                  0))
        listed (for [n xs
                     :when (pos? n)
                     :while (< n 10)]
                 n)]
    (when-let [s (seq listed)]
      (doseq [n s]
        n)
      (letfn [(local [n]
                (if (neg? n) :neg :non-neg))]
        [acc cased tried (local x)]))))
