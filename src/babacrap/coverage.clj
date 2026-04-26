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
  "Run `f` with *out* and clojure.test/*test-out* captured into a single
  string buffer. Returns {:result :captured} on success. On exception,
  replays the captured chatter to *err* before rethrowing so progress
  stays visible when something goes wrong."
  [f]
  (let [outcome (volatile! nil)
        captured (with-out-str
                   (binding [test/*test-out* *out*]
                     (try
                       (vreset! outcome {:result (f)})
                       (catch Exception e
                         (vreset! outcome {:error e})))))]
    (when-let [e (:error @outcome)]
      (replay-to-err! captured)
      (throw e))
    {:result (:result @outcome) :captured captured}))

(defn run-cloverage! [opts]
  ;; Cloverage prints via *out*; clojure.test prints via *test-out*. Capture
  ;; both so stdout stays reserved for our own result payload (`--format edn`
  ;; must be parseable). On any failure replay the captured chatter to stderr
  ;; so users can debug; on success discard it.
  (let [args (cloverage-args opts)
        {exit-code :result
         captured :captured} (capture-out
                              #(binding [cloverage/*exit-after-test* false]
                                 (apply cloverage/-main args)))]
    (when-not (zero? exit-code)
      (replay-to-err! captured)
      (throw (ex-info "Cloverage failed" {:exit-code exit-code
                                          :args args})))
    (str (fs/path (:output opts) "raw-stats.clj"))))

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
