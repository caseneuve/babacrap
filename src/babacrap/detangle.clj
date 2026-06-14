(ns babacrap.detangle
  (:gen-class)
  (:require [babacrap.complexity :as complexity]
            [babacrap.table :as table]
            [babacrap.util :as util]
            [babashka.cli :as cli]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

;; -- Pure --

(def cli-spec
  {:src    {:desc    "Source directory or file; can be repeated."
            :alias   :p
            :coerce  []
            :default ["src"]
            :ref     "<path>"}
   :format {:desc     "Output format: text or edn."
            :coerce   :keyword
            :default  :text
            :ref      "<format>"
            :validate {:pred #{:text :edn}
                       :ex-msg (fn [_] "Must be text or edn")}}
   :help   {:desc  "Print this help and exit."
            :alias :h
            :coerce :boolean}})

(def cli-order [:src :format :help])

(def hidden-context-calls
  '#{System/currentTimeMillis
     System/nanoTime
     java.lang.System/currentTimeMillis
     java.lang.System/nanoTime
     java.time.Instant/now
     java.time.LocalDate/now
     java.time.LocalDateTime/now
     java.time.ZonedDateTime/now
     rand
     rand-int
     rand-nth
     random-uuid
     java.util.UUID/randomUUID
     UUID/randomUUID})

(def dispatch-keys
  #{:type :kind :op :event :status})

(def sexpr-tags
  #{:token :list :vector :map :set :string :quote :deref})

(defn node-tag [node]
  (when node
    (n/tag node)))

(defn sexpr-value
  "Return a node's s-expression only for tags this analyzer intentionally
  treats as value forms. Other tags are not data for current detector rules."
  [node]
  (when (and node (contains? sexpr-tags (node-tag node)))
    (n/sexpr node)))

(defn node-location [node]
  (select-keys (meta node) [:row :col :end-row :end-col]))

(defn node-row [node]
  (:row (meta node)))

(defn node-col [node]
  (:col (meta node)))

(defn list-form? [node]
  (= :list (node-tag node)))

(defn vector-form? [node]
  (= :vector (node-tag node)))

(defn deref-form? [node]
  (= :deref (node-tag node)))

(defn form-head [node]
  (when (list-form? node)
    (some-> node complexity/children first complexity/token-value)))

(def skip-walk-tags
  #{:quote :syntax-quote})

(defn walk-nodes
  "Pure depth-first node sequence. Quoted code is data, so detector rules do
  not inspect it as runtime behavior."
  [node]
  (when-not (contains? skip-walk-tags (node-tag node))
    (cons node (mapcat walk-nodes (complexity/children node)))))

(defn function-info
  "Pure: attach body nodes to complexity inventory facts for one arity."
  [filename ns-sym resource-file arity]
  (let [name-sym (some-> arity :name-node sexpr-value)
        location-node (:location-node arity)
        location (node-location location-node)]
    (when name-sym
      (merge
       {:ns ns-sym
        :name name-sym
        :var (symbol (str ns-sym) (str name-sym))
        :arity-index (:arity-index arity)
        :filename filename
        :resource-file resource-file
        :body (:body arity)
        :node location-node
        :complexity (inc (complexity/complexity-of-children (:body arity)))}
       {:row (:row location)
        :col (:col location)
        :end-row (:end-row location)
        :end-col (:end-col location)}))))

(defn file-functions-from
  "Pure: extract detangle function facts from a parsed file."
  [forms-node filename]
  (when-let [ns-sym (complexity/namespace-name forms-node)]
    (let [resource-file (complexity/namespace->resource-path ns-sym filename)]
      (vec
       (for [form (complexity/children forms-node)
             :when (and (list-form? form)
                        (contains? complexity/defn-like-symbols
                                   (some-> form form-head)))
             arity (complexity/arities form)
             :let [info (function-info filename ns-sym resource-file arity)]
             :when info]
         info)))))

(defn function-nodes [{:keys [body]}]
  (mapcat walk-nodes body))

(defn finding
  [fn-info rule severity node question evidence]
  {:rule rule
   :severity severity
   :filename (:filename fn-info)
   :ns (:ns fn-info)
   :name (:name fn-info)
   :var (:var fn-info)
   :arity-index (:arity-index fn-info)
   :complexity (:complexity fn-info)
   :row (node-row node)
   :col (node-col node)
   :question question
   :evidence evidence})

(defn hidden-context-findings [fn-info]
  (for [node (function-nodes fn-info)
        :when (list-form? node)
        :let [call (form-head node)]
        :when (contains? hidden-context-calls call)]
    (finding fn-info
             :context/hidden-time-or-randomness
             2
             node
             "Can time or randomness be passed explicitly from the boundary?"
             {:call call})))

(defn keyword-access? [node]
  (when (list-form? node)
    (let [[op target] (complexity/children node)
          key (complexity/token-value op)]
      (when (and (keyword? key)
                 (contains? dispatch-keys key)
                 target)
        {:key key
         :target (sexpr-value target)}))))

(defn get-access? [node]
  (when (list-form? node)
    (let [[op target key] (complexity/children node)]
      (when (and (= 'get (complexity/token-value op))
                 (contains? dispatch-keys (sexpr-value key))
                 target)
        {:key (sexpr-value key)
         :target (sexpr-value target)}))))

(defn dispatch-access [node]
  (or (keyword-access? node)
      (get-access? node)))

(defn case-branch-count [case-node]
  (let [[_op _expr & clauses] (complexity/children case-node)]
    (complexity/count-case-tests clauses)))

(defn case-data-dispatch-findings [fn-info]
  (for [node (function-nodes fn-info)
        :when (and (list-form? node)
                   (= 'case (form-head node)))
        :let [[_op expr] (complexity/children node)
              dispatch (dispatch-access expr)
              branches (case-branch-count node)]
        :when (and dispatch (>= branches 2))]
    (finding fn-info
             :dispatch/data-dispatch
             3
             node
             "Can data-based dispatch be separated from branch behavior?"
             {:form 'case
              :dispatch-expr (sexpr-value expr)
              :dispatch-key (:key dispatch)
              :dispatch-target (:target dispatch)
              :branches branches})))

(defn cond-test-nodes [cond-node]
  (let [[_op & clauses] (complexity/children cond-node)]
    (remove #(complexity/kw-node? % :else)
            (complexity/pair-tests clauses))))

(defn equality-dispatch-test [node]
  (when (and (list-form? node)
             (= '= (form-head node)))
    (let [[_op left right & more] (complexity/children node)]
      (when-not (seq more)
        (let [left-dispatch (dispatch-access left)
              right-dispatch (dispatch-access right)]
          (cond
            left-dispatch
            (assoc left-dispatch
                   :dispatch-expr (sexpr-value left)
                   :value (sexpr-value right))

            right-dispatch
            (assoc right-dispatch
                   :dispatch-expr (sexpr-value right)
                   :value (sexpr-value left))))))))

(defn same-dispatch-key [{:keys [key target dispatch-expr]}]
  [key target dispatch-expr])

(defn repeated-cond-dispatches [cond-node]
  (->> (cond-test-nodes cond-node)
       (keep equality-dispatch-test)
       (group-by same-dispatch-key)
       (keep (fn [[_ tests]]
               (when (>= (count tests) 2)
                 (let [{:keys [key target dispatch-expr]} (first tests)]
                   {:form 'cond
                    :dispatch-expr dispatch-expr
                    :dispatch-key key
                    :dispatch-target target
                    :branches (count tests)
                    :values (mapv :value tests)}))))))

(defn cond-data-dispatch-findings [fn-info]
  (for [node (function-nodes fn-info)
        :when (and (list-form? node)
                   (= 'cond (form-head node)))
        dispatch (repeated-cond-dispatches node)]
    (finding fn-info
             :dispatch/data-dispatch
             3
             node
             "Can data-based dispatch be separated from branch behavior?"
             dispatch)))

(defn data-dispatch-findings [fn-info]
  (concat (case-data-dispatch-findings fn-info)
          (cond-data-dispatch-findings fn-info)))

(defn instance-test [node]
  (when (and (list-form? node)
             (= 'instance? (form-head node)))
    (let [[_op type-node subject-node] (complexity/children node)
          type-expr (sexpr-value type-node)
          subject-expr (sexpr-value subject-node)]
      (when (and type-expr subject-expr)
        {:type type-expr
         :subject subject-expr}))))

(defn repeated-instance-groups [cond-node]
  (->> (cond-test-nodes cond-node)
       (keep instance-test)
       (group-by :subject)
       (keep (fn [[subject tests]]
               (when (>= (count tests) 2)
                 {:subject subject
                  :types (mapv :type tests)})))))

(defn type-branching-findings [fn-info]
  (for [node (function-nodes fn-info)
        :when (and (list-form? node)
                   (= 'cond (form-head node)))
        group (repeated-instance-groups node)]
    (finding fn-info
             :dispatch/type-branching
             3
             node
             "Can type-based branching be moved behind a protocol?"
             group)))

(defn deep-vector-path [node]
  (let [path (sexpr-value node)]
    (when (and (vector? path)
               (>= (count path) 2))
      path)))

(defn get-in-call [node]
  (when (and (list-form? node)
             (= 'get-in (form-head node)))
    (let [[_op root-node path-node] (complexity/children node)
          root (sexpr-value root-node)
          path (deep-vector-path path-node)]
      (when (and (symbol? root) path)
        {:root root
         :path path
         :node node}))))

(defn predicate-name? [sym]
  (str/ends-with? (name sym) "?"))

(defn raw-shape-groups [fn-info]
  (let [minimum-paths (if (predicate-name? (:name fn-info)) 2 3)]
    (->> (function-nodes fn-info)
         (keep get-in-call)
         (group-by :root)
         (keep (fn [[root calls]]
                 (when (>= (count calls) minimum-paths)
                   {:root root
                    :paths (mapv :path calls)
                    :node (:node (first calls))}))))))

(defn raw-shape-findings [fn-info]
  (for [{:keys [node] :as group} (raw-shape-groups fn-info)]
    (finding fn-info
             :data/raw-shape-in-rule
             3
             node
             "Can raw input shape be normalized before applying the rule?"
             (dissoc group :node))))

(def let-symbols '#{let let*})

(defn partition-bindings [binding-vector]
  (partition 2 (complexity/children binding-vector)))

(defn atom-binding [binding-pair]
  (let [[name-node init-node] binding-pair
        local (sexpr-value name-node)]
    (when (and (symbol? local)
               (list-form? init-node)
               (= 'atom (form-head init-node)))
      local)))

(defn local-atom-bindings [binding-vector]
  (when (vector-form? binding-vector)
    (set (keep atom-binding (partition-bindings binding-vector)))))

(defn swap-local [locals node]
  (when (and (list-form? node)
             (contains? '#{swap! reset!} (form-head node)))
    (let [[op-node target-node] (complexity/children node)
          target (sexpr-value target-node)]
      (when (contains? locals target)
        {:op (complexity/token-value op-node)
         :local target}))))

(defn deref-target [node]
  (cond
    (deref-form? node)
    (some-> node complexity/children first sexpr-value)

    (and (list-form? node)
         (contains? '#{deref clojure.core/deref} (form-head node)))
    (some-> node complexity/children second sexpr-value)))

(defn deref-local [locals node]
  (let [target (deref-target node)]
    (when (contains? locals target)
      {:op 'deref
       :local target})))

(defn local-mutation-evidence [locals body-nodes]
  (let [nodes (mapcat walk-nodes body-nodes)
        swaps (keep #(swap-local locals %) nodes)
        derefs (keep #(deref-local locals %) nodes)]
    (for [local locals
          :let [local-swaps (filter #(= local (:local %)) swaps)
                local-derefs (filter #(= local (:local %)) derefs)]
          :when (and (seq local-swaps) (seq local-derefs))]
      {:local local
       :ops (vec (distinct (concat (map :op local-swaps)
                                   (map :op local-derefs))))})))

(defn local-mutable-accumulation-findings [fn-info]
  (for [node (function-nodes fn-info)
        :when (and (list-form? node)
                   (contains? let-symbols (form-head node)))
        :let [[_op bindings & body] (complexity/children node)
              locals (local-atom-bindings bindings)]
        :when (seq locals)
        evidence (local-mutation-evidence locals body)]
    (finding fn-info
             :state/local-mutable-accumulation
             2
             node
             "Can this local identity be replaced by a value transformation?"
             evidence)))

(def rules
  [hidden-context-findings
   data-dispatch-findings
   type-branching-findings
   raw-shape-findings
   local-mutable-accumulation-findings])

(defn analyze-function [fn-info]
  (mapcat #(% fn-info) rules))

(defn finding-sort-key [{:keys [severity filename row col rule]}]
  [(- severity) filename row col rule])

(defn report [findings]
  {:findings findings
   :summary {:total (count findings)
             :by-rule (frequencies (map :rule findings))
             :severity-sum (reduce + 0 (map :severity findings))}})

(def ^:private finding-columns
  [{:header "SEV" :align :right}
   {:header "RULE" :align :left}
   {:header "LOCATION" :align :left}
   {:header "QUESTION" :align :left}])

(defn finding-row [{:keys [severity rule filename row question] :as f}]
  [(str severity)
   (str rule)
   (format "%s:%s %s" filename row (util/function-label f))
   question])

(defn format-text [{:keys [findings summary]}]
  (str \newline
       (if (empty? findings)
         "Detangle analysis: no findings."
         (str "Detangle analysis: " (:total summary) " findings, severity sum "
              (:severity-sum summary)
              \newline
              (table/render finding-columns (map finding-row findings))))))

(defn render-report [report-data format]
  (util/render-report format-text report-data format))

(defn usage []
  (str/join
   \newline
   ["babacrap detangle: deterministic decomplecting investigation signals"
    ""
    "Usage: babacrap detangle [options]"
    "       bb detangle [options]"
    ""
    "Options:"
    (cli/format-opts {:spec cli-spec :order cli-order})
    ""
    "Examples:"
    "  bb detangle --src src"
    "  bb detangle --src src --format edn"
    "  babacrap detangle --src src --format edn"]))

(defn error-text [errors]
  (str/join \newline (concat (map :msg errors) ["" (usage)])))

;; -- Side effects --

(defn parse-file
  "IO boundary: parse source once."
  [filename]
  {:filename filename
   :forms (p/parse-file-all filename)})

(defn file-functions [filename]
  (let [{:keys [forms]} (parse-file filename)]
    (file-functions-from forms filename)))

(defn analyze-file [filename]
  (let [functions (file-functions filename)]
    (->> functions
         (mapcat analyze-function)
         (sort-by finding-sort-key)
         vec)))

(defn analyze-paths [paths]
  (->> (complexity/source-files paths)
       (mapcat analyze-file)
       (sort-by finding-sort-key)
       vec))

(defn parse-args [args]
  (let [errors (volatile! [])
        opts (cli/parse-opts args
                             {:spec cli-spec
                              :restrict true
                              :error-fn #(vswap! errors conj %)})]
    {:opts opts :errors @errors}))

(defn run-result [args]
  (let [{:keys [opts errors]} (parse-args args)]
    (cond
      (or (:help opts) (empty? args))
      {:exit 0
       :out (usage)}

      (seq errors)
      {:exit 2
       :err (error-text errors)}

      :else
      (let [report-data (report (analyze-paths (:src opts)))]
        {:exit 0
         :out (render-report report-data (:format opts))}))))

(defn run [args]
  (let [{:keys [exit] :as result} (run-result args)]
    (util/emit-result result)
    exit))

;; -- CLI entry point --

(defn -main [& args]
  (util/exit-nonzero! (run args)))
