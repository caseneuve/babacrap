(ns babacrap.util)

(defn merge-with-defaults
  "Merge `opts` over `defaults`, dropping keys in `opts` whose value is an
  empty vector. Repeatable CLI flags default to `[]` — callers want the
  real defaults back when nothing was passed."
  [defaults opts]
  (merge defaults (into {} (remove (fn [[_ v]] (= [] v)) opts))))
