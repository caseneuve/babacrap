(ns io.github.caseneuve.babacrap
  (:require [clj-kondo.hooks-api :as api]))

(def linter :babacrap/cyclomatic-complexity)

(defn token-value [node]
  (when (api/token-node? node)
    (:value node)))

(defn call-sym [node]
  (when (api/list-node? node)
    (some-> node :children first token-value)))

(defn kw-node? [node kw]
  (and (api/keyword-node? node)
       (= kw (api/sexpr node))))

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
  (if-not (api/vector-node? binding-vec)
    0
    (count
     (filter #(contains? #{:when :while} (api/sexpr %))
             (filter api/keyword-node? (:children binding-vec))))))

(defn complexity* [node]
  (cond
    (nil? node)
    0

    ;; Quoted code is data, not runtime control flow.
    (api/quote-node? node)
    0

    (api/list-node? node)
    (let [[op & args] (:children node)
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

    (or (api/vector-node? node)
        (api/map-node? node)
        (api/set-node? node))
    (complexity-of-children (:children node))

    :else
    0))

(defn option-node? [node]
  ;; defn-like optional docstring and attr-map before arities.
  (or (api/string-node? node)
      (api/map-node? node)))

(defn arities [node]
  (let [[_defn name-node & more] (:children node)
        more (drop-while option-node? more)]
    (if (api/vector-node? (first more))
      ;; Single arity:
      ;; (defn f [x] ...)
      [{:name-node name-node
        :location-node name-node
        :body (rest more)}]

      ;; Multi arity:
      ;; (defn f
      ;;   ([x] ...)
      ;;   ([x y] ...))
      (for [arity more
            :when (api/list-node? arity)]
        (let [[_argv & body] (:children arity)
              body (if (api/map-node? (first body))
                     (rest body)
                     body)]
          {:name-node name-node
           :location-node arity
           :body body})))))

(defn configured-max [config]
  (or (get-in config [:linters linter :max])
      10))

(defn defn-like [{:keys [node config]}]
  (let [max-complexity (configured-max config)]
    (doseq [{:keys [name-node location-node body]} (arities node)]
      ;; Cyclomatic complexity has base score 1.
      (let [score (inc (complexity-of-children body))]
        (when (> score max-complexity)
          (api/reg-finding!
           (assoc (meta (or location-node name-node node))
                  :type linter
                  :message
                  (format "Cyclomatic complexity of `%s` is %d (max %d)."
                          (api/sexpr name-node)
                          score
                          max-complexity))))))))
