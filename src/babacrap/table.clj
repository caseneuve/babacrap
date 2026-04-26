(ns babacrap.table
  (:require [clojure.string :as str]))

(defn pad-right [s width]
  (let [s (str s)]
    (str s (str/join (repeat (max 0 (- width (count s))) \space)))))

(defn pad-left [s width]
  (let [s (str s)]
    (str (str/join (repeat (max 0 (- width (count s))) \space)) s)))

(defn column-widths [headers rows]
  (apply mapv
         (fn [& cells] (apply max (map (comp count str) cells)))
         headers
         rows))

(defn row-string [aligns widths cells]
  (let [pad (fn [align w c]
              (if (= align :right) (pad-left c w) (pad-right c w)))]
    (str " "
         (str/join " | " (map pad aligns widths (map str cells)))
         " ")))

(defn separator [widths]
  (str/join "-+-" (map #(str/join (repeat % \-)) widths)))

(defn render
  "Render a fixed-width text table.
  `cols` is a seq of {:header :align} maps. `rows` is a seq of cell seqs."
  [cols rows]
  (let [headers (map :header cols)
        aligns (map (fnil identity :left) (map :align cols))
        widths (column-widths headers rows)]
    (str/join
     \newline
     (concat [(row-string aligns widths headers)
              (str "-" (separator widths) "-")]
             (map #(row-string aligns widths %) rows)))))
