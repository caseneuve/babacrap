(ns babacrap.complexity
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(def defn-like-symbols '#{defn defn- defmacro})

(def ignored-tags
  #{:whitespace :newline :comment :comma :uneval})

(defn semantic? [node]
  (not (contains? ignored-tags (n/tag node))))

(defn children [node]
  (filter semantic? (:children node)))

(defn token-value [node]
  (when (= :token (n/tag node))
    (n/sexpr node)))

(defn call-sym [node]
  (when (= :list (n/tag node))
    (some-> node children first token-value)))

(defn kw-node? [node kw]
  (and (= :token (n/tag node))
       (= kw (n/sexpr node))))

(defn pair-tests [nodes]
  (map first (partition 2 nodes)))

(defn count-case-tests [nodes]
  ;; `nodes` are everything after `(case expr ...)` or `(condp pred expr ...)`.
  ;; If the count is odd, the final node is the default expression.
  (let [clauses (if (odd? (count nodes))
                  (butlast nodes)
                  nodes)]
    (count (partition 2 clauses))))

(declare complexity*)

(defn complexity-of-children [nodes]
  (reduce + 0 (map complexity* nodes)))

(defn binding-filter-complexity [binding-vec]
  (if-not (= :vector (n/tag binding-vec))
    0
    (count
     (filter #(contains? #{:when :while} (n/sexpr %))
             (filter #(= :token (n/tag %)) (children binding-vec))))))

(defn complexity* [node]
  (cond
    (nil? node)
    0

    ;; Quoted code is data, not runtime control flow.
    (= :quote (n/tag node))
    0

    (= :list (n/tag node))
    (let [[op & args] (children node)
          sym (token-value op)]
      (case sym
        ;; Branching forms.
        (if if-not if-let if-some when when-not when-let when-some)
        (+ 1 (complexity-of-children args))

        cond
        (let [tests (remove #(kw-node? % :else)
                            (pair-tests args))]
          (+ (count tests)
             (complexity-of-children args)))

        (cond-> cond->>)
        (let [[expr & clauses] args
              tests (remove #(kw-node? % :else)
                            (pair-tests clauses))]
          (+ (count tests)
             (complexity* expr)
             (complexity-of-children clauses)))

        condp
        (let [[expr & clauses] args]
          (+ (count-case-tests clauses)
             (complexity* expr)
             (complexity-of-children clauses)))

        case
        (let [[expr & clauses] args]
          (+ (count-case-tests clauses)
             (complexity* expr)
             (complexity-of-children clauses)))

        ;; Short-circuit boolean operators add paths.
        (and or)
        (+ (max 0 (dec (count args)))
           (complexity-of-children args))

        ;; Each catch adds a path. finally does not.
        try
        (+ (count (filter #(= 'catch (call-sym %)) args))
           (complexity-of-children args))

        ;; Sequence comprehensions / loops.
        (for doseq)
        (let [[bindings & body] args]
          (+ 1
             (binding-filter-complexity bindings)
             (complexity-of-children body)))

        ;; Do not charge an outer function for nested function bodies.
        (fn fn* defn defn- defmacro)
        0

        ;; Skip local function bodies but still count the letfn body.
        letfn
        (let [[_bindings & body] args]
          (complexity-of-children body))

        ;; Default: recurse.
        (complexity-of-children args)))

    (contains? #{:vector :map :set} (n/tag node))
    (complexity-of-children (children node))

    :else
    0))

(defn option-node? [node]
  ;; defn-like optional docstring and attr-map before arities.
  (contains? #{:string :map} (n/tag node)))

(defn arities [defn-node]
  (let [[_defn name-node & more] (children defn-node)
        more (drop-while option-node? more)]
    (if (= :vector (some-> (first more) n/tag))
      ;; Single arity:
      ;; (defn f [x] ...)
      [{:name-node name-node
        :location-node defn-node
        :arity-index 0
        :body (rest more)}]

      ;; Multi arity:
      ;; (defn f
      ;;   ([x] ...)
      ;;   ([x y] ...))
      (keep-indexed
       (fn [idx arity]
         (when (= :list (n/tag arity))
           (let [[_argv & body] (children arity)
                 body (if (= :map (some-> (first body) n/tag))
                        (rest body)
                        body)]
             {:name-node name-node
              :location-node arity
              :arity-index idx
              :body body})))
       more))))

(defn namespace-name [forms-node]
  (some (fn [form]
          (when (and (= :list (n/tag form))
                     (= 'ns (some-> form children first token-value)))
            (some-> form children second n/sexpr)))
        (children forms-node)))

(defn namespace->resource-path [ns-sym filename]
  (let [base (-> (name ns-sym)
                 (str/replace "-" "_")
                 (str/replace "." "/"))
        ext (or (second (re-find #"(\.[^.]+)$" filename))
                ".clj")]
    (str base ext)))

(defn source-file? [^java.io.File f]
  (and (.isFile f)
       (re-find #"\.(clj|cljc|bb)$" (.getName f))))

(defn source-files [paths]
  (->> paths
       (map io/file)
       (mapcat (fn [^java.io.File f]
                 (cond
                   (not (.exists f)) []
                   (.isDirectory f) (file-seq f)
                   :else [f])))
       (filter source-file?)
       (map #(.getPath ^java.io.File %))))

(defn analyze-file [filename]
  (let [forms-node (p/parse-file-all filename)
        ns-sym (namespace-name forms-node)]
    (when ns-sym
      (let [resource-file (namespace->resource-path ns-sym filename)]
        (vec
         (for [form (children forms-node)
               :when (and (= :list (n/tag form))
                          (contains? defn-like-symbols
                                     (some-> form children first token-value)))
               arity (arities form)
               :let [name-sym (some-> arity :name-node n/sexpr)
                     location-node (:location-node arity)
                     m (meta location-node)
                     score (inc (complexity-of-children (:body arity)))]
               :when name-sym]
           {:ns ns-sym
            :name name-sym
            :var (symbol (str ns-sym) (str name-sym))
            :arity-index (:arity-index arity)
            :filename filename
            :resource-file resource-file
            :row (:row m)
            :col (:col m)
            :end-row (:end-row m)
            :end-col (:end-col m)
            :complexity score}))))))

(defn analyze-paths [paths]
  (mapcat analyze-file (source-files paths)))
