(ns good)

(defn simple [x]
  (if x
    :yes
    :no))

(defn multi-arity
  ([x]
   (if x :yes :no))
  ([x y]
   (if x
     (inc y)
     (dec y))))

(defn nested-fn-does-not-count-against-outer [xs]
  (map (fn [x]
         (if x :yes :no))
       xs))
