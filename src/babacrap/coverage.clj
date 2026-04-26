(ns babacrap.coverage
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as test]
            [cloverage.coverage :as cloverage]))

(defn cloverage-args [{:keys [src-paths test-paths ns-regex test-ns-regex output]}]
  (vec
   (concat
    ["--output" output
     "--no-html"
     "--raw"]
    (mapcat (fn [p] ["--src-ns-path" p]) src-paths)
    (mapcat (fn [p] ["--test-ns-path" p]) test-paths)
    (mapcat (fn [r] ["--ns-regex" r]) ns-regex)
    (mapcat (fn [r] ["--test-ns-regex" r]) test-ns-regex))))

(defn- replay-to-err! [captured]
  (binding [*out* *err*] (print captured) (flush)))

(defn capture-out
  "Imperative shell helper: run `f` with `*out*` and `clojure.test/*test-out*`
  redirected into a shared buffer, and return a pure description of the
  outcome — never throws. Callers decide what to do with errors.

  Returns one of:
    {:ok result :captured s}  — f returned `result`
    {:error e :captured s}    — f threw `e` (captured text still available)

  A `volatile!` carries the result out of `with-out-str` since that macro
  only returns the captured string. This is the one place in babacrap that
  uses process-level mutable state; do not pattern-match on it elsewhere."
  [f]
  (let [outcome (volatile! nil)
        captured (with-out-str
                   (binding [test/*test-out* *out*]
                     (try
                       (vreset! outcome {:ok (f)})
                       (catch Exception e
                         (vreset! outcome {:error e})))))]
    (assoc @outcome :captured captured)))

(defn run-cloverage! [opts]
  ;; Cloverage prints via *out*; clojure.test prints via *test-out*. Capture
  ;; both so stdout stays reserved for our own result payload (`--format edn`
  ;; must be parseable). On any failure replay the captured chatter to stderr
  ;; so users can debug; on success discard it.
  (let [args (cloverage-args opts)
        {:keys [ok error captured]}
        (capture-out #(binding [cloverage/*exit-after-test* false]
                        (apply cloverage/-main args)))]
    (cond
      error
      (do (replay-to-err! captured) (throw error))

      (not (zero? ok))
      (do (replay-to-err! captured)
          (throw (ex-info "Cloverage failed" {:exit-code ok :args args})))

      :else
      (str (fs/path (:output opts) "raw-stats.clj")))))

(defn read-raw-stats [raw-stats-file]
  ;; Cloverage raw stats are printed with clojure.pprint, not as strict EDN.
  ;; Source forms can contain reader literals like regexes, so use the Clojure
  ;; reader with eval disabled instead of clojure.edn/read-string.
  (binding [*read-eval* false]
    (read-string (slurp raw-stats-file))))

(defn covered-form? [form]
  (and (:tracked form)
       (:covered form)))

(defn tracked-form? [form]
  (:tracked form))

(defn file-matches? [coverage-file complexity-resource-file complexity-filename]
  (or (= coverage-file complexity-resource-file)
      (= coverage-file complexity-filename)
      (and (str/includes? coverage-file "/")
           (fs/ends-with? (fs/path complexity-filename)
                          (fs/path coverage-file)))))

(defn in-range? [{:keys [row end-row]} {:keys [line]}]
  (and line
       (<= row line)
       (<= line end-row)))

(defn function-coverage [raw-stats {:keys [resource-file filename] :as function}]
  (let [forms (filter #(and (file-matches? (:file %) resource-file filename)
                            (in-range? function %)
                            (tracked-form? %))
                      raw-stats)
        total (count forms)
        covered (count (filter covered-form? forms))]
    {:tracked-forms total
     :covered-forms covered
     :coverage (if (pos? total)
                 (/ covered total)
                 0.0)}))
