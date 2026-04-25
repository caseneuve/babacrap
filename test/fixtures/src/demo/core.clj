(ns demo.core)

(defn simple [x]
  (if x
    :yes
    :no))

(defn uncovered-complex [x y]
  (cond
    (and x y) :both
    x :x
    :else (if y :y :neither)))

(defn partially-covered [x]
  (case x
    :a 1
    :b 2
    0))
