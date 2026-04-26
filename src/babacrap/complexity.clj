(ns babacrap.complexity
  (:require [babashka.fs :as fs]
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
       (keyword? (n/sexpr node))
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
    (count (filter #(or (kw-node? % :when)
                        (kw-node? % :while))
                   (children binding-vec)))))

(def branch-symbols
  '#{if if-not if-let if-some when when-not when-let when-some})

(def conditional-threading-symbols '#{cond-> cond->>})

(def case-like-symbols '#{condp case})

(def boolean-symbols '#{and or})

(def comprehension-symbols '#{for doseq})

(def nested-function-symbols '#{fn fn* defn defn- defmacro})

(def container-tags #{:vector :map :set})

(defn non-else-tests [nodes]
  (remove #(kw-node? % :else) (pair-tests nodes)))

(defn cond-complexity [args]
  (+ (count (non-else-tests args))
     (complexity-of-children args)))

(defn conditional-threading-complexity [[expr & clauses]]
  (+ (count (non-else-tests clauses))
     (complexity* expr)
     (complexity-of-children clauses)))

(defn case-like-complexity [[expr & clauses]]
  (+ (count-case-tests clauses)
     (complexity* expr)
     (complexity-of-children clauses)))

(defn boolean-complexity [args]
  (+ (max 0 (dec (count args)))
     (complexity-of-children args)))

(defn try-complexity [args]
  (+ (count (filter #(= 'catch (call-sym %)) args))
     (complexity-of-children args)))

(defn comprehension-complexity [[bindings & body]]
  (+ 1
     (binding-filter-complexity bindings)
     (complexity-of-children body)))

(defn letfn-complexity [[_bindings & body]]
  (complexity-of-children body))

(defn list-complexity [node]
  (let [[op & args] (children node)
        sym (token-value op)]
    (cond
      (contains? branch-symbols sym)
      (+ 1 (complexity-of-children args))

      (= 'cond sym)
      (cond-complexity args)

      (contains? conditional-threading-symbols sym)
      (conditional-threading-complexity args)

      (contains? case-like-symbols sym)
      (case-like-complexity args)

      (contains? boolean-symbols sym)
      (boolean-complexity args)

      (= 'try sym)
      (try-complexity args)

      (contains? comprehension-symbols sym)
      (comprehension-complexity args)

      ;; Do not charge an outer function for nested function bodies.
      (contains? nested-function-symbols sym)
      0

      ;; Skip local function bodies but still count the letfn body.
      (= 'letfn sym)
      (letfn-complexity args)

      :else
      (complexity-of-children args))))

(defn complexity* [node]
  (cond
    (nil? node) 0

    ;; Quoted code is data, not runtime control flow.
    (= :quote (n/tag node)) 0

    (= :list (n/tag node))
    (list-complexity node)

    (contains? container-tags (n/tag node))
    (complexity-of-children (children node))

    :else 0))

(defn option-node? [node]
  ;; defn-like optional docstring and attr-map before arities.
  (contains? #{:string :map} (n/tag node)))

(defn metadata-node? [node]
  (contains? #{:meta :reader-macro} (n/tag node)))

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
            (some->> (children form)
                     rest
                     (drop-while metadata-node?)
                     first
                     n/sexpr)))
        (children forms-node)))

(defn namespace->resource-path [ns-sym filename]
  (let [base (-> (name ns-sym)
                 (str/replace "-" "_")
                 (str/replace "." "/"))
        ext (or (second (re-find #"(\.[^.]+)$" filename))
                ".clj")]
    (str base ext)))

(def source-extensions #{"clj" "cljc" "bb"})

(defn source-file? [path]
  (and (fs/regular-file? path)
       (contains? source-extensions (fs/extension path))))

(defn expand-path [path]
  (cond
    (not (fs/exists? path)) []
    (fs/directory? path) (fs/glob path "**{.clj,.cljc,.bb}")
    :else [path]))

(defn source-files [paths]
  (->> paths
       (map fs/path)
       (mapcat expand-path)
       (filter source-file?)
       (map str)
       sort))

(defn analyze-forms
  "Pure: extract complexity facts for every defn-like form in `forms-node`.
  `filename` is only used to tag results and derive the resource path."
  [forms-node filename]
  (when-let [ns-sym (namespace-name forms-node)]
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
          :complexity score})))))

(defn analyze-file [filename]
  (analyze-forms (p/parse-file-all filename) filename))

(defn analyze-paths [paths]
  (mapcat analyze-file (source-files paths)))
