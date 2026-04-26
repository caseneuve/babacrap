(ns babacrap.util)

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
