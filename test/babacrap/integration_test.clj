(ns babacrap.integration-test
  (:require [babacrap.complexity :as complexity]
            [babacrap.coverage :as coverage]
            [babacrap.core :as babacrap]
            [babacrap.mutation :as mutation]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cloverage.coverage :as cloverage]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as parser]))

(defn run-clj-kondo [& args]
  (let [{:keys [exit out err]}
        (apply p/shell {:out :string :err :string :continue true}
               "clj-kondo" args)]
    (is (zero? exit) (str err "\n" out))
    (edn/read-string out)))

(deftest clj-kondo-hook-test
  (testing "complexity hook reports configured threshold crossings"
    (let [hook-result (run-clj-kondo "--lint" "test/corpus"
                                     "--cache" "false"
                                     "--fail-level" "error"
                                     "--config" "{:linters {:babacrap/cyclomatic-complexity {:level :warning :max 3}} :output {:format :edn}}")
          findings (:findings hook-result)
          messages (map :message findings)]
      (is (= 2 (count findings)))
      (is (some #(str/includes? % "`complicated` is 5") messages))
      (is (some #(str/includes? % "`complicated-multi-arity` is 4") messages)))))

(defn hook-score [message]
  (let [[_ fn-name score] (re-find #"Cyclomatic complexity of `([^`]+)` is (\d+)" message)]
    [(symbol fn-name) (parse-long score)]))

(deftest hook-cli-parity-test
  (testing "clj-kondo hook and CLI complexity analysis agree on fixture scores"
    (let [hook-result (run-clj-kondo "--lint" "test/fixtures/src"
                                     "--cache" "false"
                                     "--fail-level" "error"
                                     "--config" "{:linters {:babacrap/cyclomatic-complexity {:level :warning :max 0}} :output {:format :edn}}")
          hook-scores (->> (:findings hook-result)
                           (filter #(= :babacrap/cyclomatic-complexity (:type %)))
                           (map (comp hook-score :message))
                           frequencies)
          cli-scores (->> (complexity/analyze-paths ["test/fixtures/src"])
                          (map (juxt :name :complexity))
                          frequencies)]
      (is (= cli-scores hook-scores)))))

(deftest crap-analysis-test
  (testing "CRAP scores combine complexity and coverage"
    (fs/delete-tree "target/test-coverage" {:force true})
    (let [crap-results (babacrap/analyze {:src-paths ["test/fixtures/src"]
                                          :test-paths ["test/fixtures/test"]
                                          :ns-regex ["demo.*"]
                                          :test-ns-regex [".*-test"]
                                          :output "target/test-coverage"
                                          :coverage? true})
          by-name (into {} (map (juxt :name identity)) crap-results)]
      (doseq [name '[simple uncovered-complex partially-covered]]
        (is (contains? by-name name)))
      (is (= 5 (:complexity (by-name 'uncovered-complex))))
      (is (> (:crap (by-name 'uncovered-complex))
             (:crap (by-name 'simple)))))))

(deftest crap-edn-stdout-is-clean-test
  (testing "stdout is parseable EDN and stderr is quiet on success"
    (fs/delete-tree "target/test-edn" {:force true})
    (let [err (java.io.StringWriter.)
          stdout (with-out-str
                   (binding [*err* err]
                     (babacrap/-main "--format" "edn"
                                     "--src" "test/fixtures/src"
                                     "--test" "test/fixtures/test"
                                     "--ns-regex" "demo.*"
                                     "--test-ns-regex" ".*-test"
                                     "--output" "target/test-edn"
                                     "--crap-threshold" "999")))
          parsed (edn/read-string stdout)]
      (is (map? parsed))
      (is (contains? parsed :results))
      (is (contains? parsed :threshold))
      (is (str/blank? (str err))
          (str "expected stderr to be empty on success, got:\n" err)))))

(deftest crap-cloverage-chatter-surfaces-on-failure-test
  (testing "captured cloverage output is forwarded to stderr when cloverage fails"
    (with-redefs [cloverage/-main
                  (fn [& _] (println "boom-chatter") 1)]
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (try
            (coverage/run-cloverage! {:src-paths ["src"]
                                      :test-paths ["test"]
                                      :ns-regex [".*"]
                                      :test-ns-regex [".*-test"]
                                      :output "target/test-edn"})
            (catch Exception _ nil)))
        (is (str/includes? (str err) "boom-chatter"))))))

(deftest crap-cli-run-test
  (testing "CRAP CLI run supports no-coverage mode"
    (is (zero? (:exit (babacrap/run-result ["--no-coverage"
                                            "--src" "test/fixtures/src"
                                            "--format" "edn"
                                            "--crap-threshold" "999"])))))
  (testing "CRAP CLI exit code follows threshold failures"
    (with-redefs [babacrap/analyze (fn [_]
                                     [{:var 'demo/f
                                       :arity-index 0
                                       :filename "src/demo.clj"
                                       :row 1
                                       :complexity 5
                                       :coverage 0.0
                                       :tracked-forms 10
                                       :covered-forms 0
                                       :crap 30.1}])]
      (is (= 1 (:exit (babacrap/run-result ["--format" "edn" "--crap-threshold" "30"]))))
      (is (= 0 (:exit (babacrap/run-result ["--format" "edn" "--crap-threshold" "31"]))))))
  (testing "CRAP CLI returns usage exit codes for help and invalid args"
    (is (= 0 (:exit (babacrap/run-result ["--help"]))))
    (let [{:keys [exit err]} (babacrap/run-result ["--format" "json"])]
      (is (= 2 exit))
      (is (str/includes? err "Must be text or edn")))))

(deftest core-rendering-test
  (let [result {:var 'demo/f
                :arity-index 0
                :filename "src/demo.clj"
                :row 1
                :complexity 5
                :coverage 0.5
                :tracked-forms 10
                :covered-forms 5
                :crap 11.25}]
    (testing "output starts with a blank line to separate from prior output"
      (is (str/starts-with? (babacrap/format-text {:results [] :failures [] :threshold 30.0})
                            "\n")))
    (testing "PASS header when no results exceed the threshold"
      (let [out (babacrap/format-text {:results [result] :failures [] :threshold 30.0})
            header (second (str/split-lines out))]
        (is (str/starts-with? header "CRAP analysis: PASS"))
        (is (str/includes? header "0/1"))
        (is (str/includes? header "30.00"))))
    (testing "FAIL header when any result exceeds the threshold"
      (let [failing (assoc result :crap 42.0)
            out (babacrap/format-text {:results [failing] :failures [failing] :threshold 30.0})
            header (second (str/split-lines out))]
        (is (str/starts-with? header "CRAP analysis: FAIL"))
        (is (str/includes? header "1/1"))))
    (testing "empty results render a PASS header"
      (let [out (babacrap/format-text {:results [] :failures [] :threshold 30.0})]
        (is (str/includes? out "CRAP analysis: PASS"))
        (is (str/includes? out "no functions"))))
    (testing "table carries each result's summary"
      (let [out (babacrap/format-text {:results [result] :failures [] :threshold 30.0})
            lines (str/split-lines out)]
        (is (some #(re-find #"(?i)CRAP\s+\|\s+COMPLEX\s+\|\s+COVERAGE\s+\|\s+LOCATION" %) lines))
        (is (some #(and (str/includes? % "11.25")
                        (str/includes? % "50.0%")
                        (str/includes? % "src/demo.clj:1 demo/f"))
                  lines))))
    (testing "core emit-result writes to the requested stream"
      (is (= "hello\n" (with-out-str (babacrap/emit-result {:out "hello"}))))
      (is (= "oops\n" (with-out-str (binding [*err* *out*]
                                        (babacrap/emit-result {:err "oops"}))))))))

(deftest complexity-rules-test
  (testing "complexity analysis counts control-flow forms and ignores data"
    (is (zero? (complexity/complexity* (parser/parse-string "'(if x y z)"))))
    (is (= 1 (complexity/complexity* (parser/parse-string "(if x y z)"))))
    (is (= 2 (complexity/complexity* (parser/parse-string "(cond a 1 b 2 :else 3)"))))
    (is (= 2 (complexity/complexity* (parser/parse-string "(and a b c)"))))
    (is (= 1 (complexity/complexity* (parser/parse-string "(try a (catch Exception e b) (finally c))"))))
    (is (= 3 (complexity/complexity* (parser/parse-string "(for [x xs :when p :while q] x)"))))
    (is (zero? (complexity/complexity* (parser/parse-string "(fn [x] (if x 1 2))"))))))

(deftest coverage-helper-test
  (testing "coverage file matching requires a path boundary"
    (is (coverage/file-matches? "demo/core.clj" "demo/core.clj" "test/fixtures/src/demo/core.clj"))
    (is (not (coverage/file-matches? "core.clj" "demo/core.clj" "test/fixtures/src/demo/core.clj")))
    (is (not (coverage/file-matches? "core.clj" "other/core.clj" "test/fixtures/src/demo/core.clj")))))

(deftest mutation-rendering-test
  (let [mutant {:id 1 :filename "src/demo.clj" :row 5 :col 3
                :mutator :replace-token :original "if" :replacement "if-not"
                :function {:var 'demo/f}}]
    (testing "output starts with a blank line to separate from prior output"
      (is (str/starts-with? (mutation/format-text []) "\n")))
    (testing "PASS header when nothing survived"
      (let [out (mutation/format-text [(assoc mutant :status :killed)])
            header (second (str/split-lines out))]
        (is (str/starts-with? header "Mutation analysis: PASS"))
        (is (str/includes? header "1 killed"))
        (is (str/includes? header "score 100.0%"))))
    (testing "FAIL header when mutants survived"
      (let [out (mutation/format-text [(assoc mutant :status :survived)])
            header (second (str/split-lines out))]
        (is (str/starts-with? header "Mutation analysis: FAIL"))
        (is (str/includes? header "1 survived"))))
    (testing "empty results render a PASS header"
      (let [out (mutation/format-text [])]
        (is (str/includes? out "Mutation analysis: PASS"))
        (is (str/includes? out "no mutants"))))
    (testing "table carries each mutant's summary"
      (let [out (mutation/format-text [(assoc mutant :status :survived)])
            lines (str/split-lines out)]
        (is (some #(re-find #"(?i)ID\s+\|\s+STATUS\s+\|\s+LOCATION\s+\|\s+MUTATION" %) lines))
        (is (some #(and (str/includes? % "#1")
                        (str/includes? % "survived")
                        (str/includes? % "src/demo.clj:5:3")
                        (str/includes? % "\"if\" => \"if-not\""))
                  lines))))))

(deftest mutation-helper-test
  (testing "mutation helpers reject non-matching inputs"
    (is (nil? (complexity/token-value (parser/parse-string "[x]"))))
    (is (nil? (mutation/function-for-range
               [{:row 10 :end-row 20 :var 'demo/f}]
               {:row 1 :end-row 2})))
    (is (nil? (mutation/node-range [0] (with-meta (n/token-node 'x) {:row 1 :col 1})))))
  (testing "quoted data is not mutated"
    (let [source "(ns demo.q)\n(defn f [] '(if true 1 2))"
          node (parser/parse-string-all source)
          line-starts (mutation/line-start-offsets source)
          functions [{:row 2 :end-row 2 :ns 'demo.q :name 'f :var 'demo.q/f :complexity 1}]]
      (is (empty? (mutation/collect* source line-starts "demo/q.clj" functions node))))))

(def fixture-test-command
  "bb -cp test/fixtures/src:test/fixtures/test -e \"(require '[clojure.test :as t] 'demo.core-test) (let [r (t/run-tests 'demo.core-test)] (System/exit (+ (:fail r) (:error r))))\"")

(deftest mutation-analysis-test
  (testing "fixture mutation results are stable"
    (let [mutation-results (mutation/run-mutation-analysis {:src-paths ["test/fixtures/src/demo/core.clj"]
                                                            :test-command fixture-test-command
                                                            :timeout-ms 5000
                                                            :limit nil
                                                            :format :edn})
          mutation-summary (mutation/summarize mutation-results)]
      (is (>= (:total mutation-summary) 5))
      (is (pos? (:killed mutation-summary)))
      (is (= (:total mutation-summary)
             (+ (:killed mutation-summary)
                (:survived mutation-summary)
                (:timeout mutation-summary)))))))

(deftest mutation-emit-result-test
  (testing "mutation emit-result writes to the requested stream"
    (is (= "hello\n" (with-out-str (mutation/emit-result {:out "hello"}))))
    (is (= "oops\n" (with-out-str (binding [*err* *out*]
                                      (mutation/emit-result {:err "oops"})))))))

(deftest mutation-cli-run-test
  (testing "mutation CLI returns non-zero when mutants survive"
    (is (= 1 (:exit (mutation/run-result ["--src" "test/fixtures/src/demo/core.clj"
                                          "--test-command" fixture-test-command
                                          "--timeout-ms" "5000"
                                          "--format" "edn"]))))))

(defn git [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
         "git" args))

(defn init-repo! [dir]
  (git dir "init" "-q" "-b" "main")
  (git dir "config" "user.email" "test@example.com")
  (git dir "config" "user.name" "test")
  (git dir "commit" "-q" "--allow-empty" "-m" "init"))

(deftest dirty-targets-test
  (testing "dirty-targets reports only the files with uncommitted changes"
    (let [dir (fs/create-temp-dir {:prefix "babacrap-dirty"})]
      (try
        (init-repo! dir)
        (let [tracked (str (fs/path dir "a.clj"))
              untouched (str (fs/path dir "b.clj"))]
          (spit tracked "(ns a)\n")
          (spit untouched "(ns b)\n")
          (git dir "add" "a.clj" "b.clj")
          (git dir "commit" "-q" "-m" "seed")
          (spit tracked "(ns a)\n;; dirty\n")
          (is (= #{tracked} (set (mutation/dirty-targets [tracked untouched]))))
          (is (empty? (mutation/dirty-targets [untouched]))))
        (finally (fs/delete-tree dir)))))
  (testing "dirty-targets returns empty outside a git repo"
    (let [dir (fs/create-temp-dir {:prefix "babacrap-nogit"})]
      (try
        (let [f (str (fs/path dir "a.clj"))]
          (spit f "(ns a)\n")
          (is (empty? (mutation/dirty-targets [f]))))
        (finally (fs/delete-tree dir))))))

(deftest mutation-run-restores-backups-before-collection-test
  (testing "CLI restores leftover backups before collecting mutants"
    (let [dir (fs/create-temp-dir {:prefix "babacrap-leftover"})]
      (try
        (let [src (str (fs/path dir "demo.clj"))
              backup (str src mutation/backup-suffix)
              original "(ns demo)\n(defn f [x] (if x :y :n))\n"
              mutated "(ns demo)\n((((\n"]
          (spit src mutated)
          (spit backup original)
          (mutation/run-result ["--src" src
                                "--test-command" "true"
                                "--force"
                                "--format" "edn"])
          (is (= original (slurp src)))
          (is (not (fs/exists? backup))))
        (finally (fs/delete-tree dir))))))

(deftest mutation-refuses-dirty-targets-test
  (testing "mutation CLI exits 3 and does not run when targets are dirty"
    (with-redefs [mutation/collect-mutants (fn [_] [{:filename "src/foo.clj"}])
                  mutation/dirty-targets (fn [_] ["src/foo.clj"])
                  mutation/run-mutation-analysis
                  (fn [_] (throw (ex-info "should not run" {})))]
      (let [{:keys [exit err]} (mutation/run-result ["--src" "src/foo.clj"
                                                     "--test-command" "true"])]
        (is (= 3 exit))
        (is (str/includes? err "Mutation targets have uncommitted changes:")))))
  (testing "--force overrides the dirty-targets refusal"
    (with-redefs [mutation/collect-mutants (fn [_] [{:filename "src/foo.clj"}])
                  mutation/dirty-targets (fn [_] ["src/foo.clj"])
                  mutation/run-mutation-analysis
                  (fn [_]
                    [{:id 1
                      :filename "src/foo.clj"
                      :row 1
                      :col 1
                      :mutator :replace-token
                      :original "x"
                      :replacement "y"
                      :status :survived
                      :function {:var 'src/foo}}])]
      (let [{:keys [exit out]} (mutation/run-result ["--src" "src/foo.clj"
                                                     "--test-command" "true"
                                                     "--force"
                                                     "--format" "edn"])]
        (is (= 1 exit))
        (is (str/includes? out ":survived"))))))
