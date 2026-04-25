(ns bad)

(defn complicated [x y]
  (cond
    (and x y) :both
    x :x
    :else (if y :y :neither)))

(defn complicated-multi-arity
  ([x]
   (if x :yes :no))
  ([x y z]
   (case [x y z]
     [true true true] :all
     [true true false] :two
     [true false false] :one
     :none)))
