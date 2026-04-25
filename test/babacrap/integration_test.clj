(ns babacrap.integration-test
  (:require [babacrap.core :as babacrap]
            [babacrap.mutation :as mutation]
            [babashka.fs :as fs]
            [babashka.process :as p]
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

(deftest crap-cli-run-test
  (testing "CRAP CLI run supports no-coverage mode"
    (let [exit (atom nil)]
      (with-out-str
        (reset! exit (babacrap/run ["--no-coverage"
                                    "--src" "test/fixtures/src"
                                    "--format" "edn"
                                    "--crap-threshold" "999"])))
      (is (zero? @exit)))))

(deftest mutation-helper-test
  (testing "mutation helpers reject non-matching inputs"
    (is (nil? (mutation/token-value (parser/parse-string "[x]"))))
    (is (nil? (mutation/function-for-range
               [{:row 10 :end-row 20 :var 'demo/f}]
               {:row 1 :end-row 2})))
    (is (nil? (mutation/node-range [0] (with-meta (n/token-node 'x) {:row 1 :col 1}))))))

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
      (is (= {:total 7
              :killed 2
              :survived 5
              :timeout 0
              :mutation-score (* 100.0 (/ 2 7))}
             mutation-summary)))))

(deftest mutation-cli-run-test
  (testing "mutation CLI returns non-zero when mutants survive"
    (let [exit (atom nil)]
      (with-out-str
        (reset! exit (mutation/run ["--src" "test/fixtures/src/demo/core.clj"
                                    "--test-command" fixture-test-command
                                    "--timeout-ms" "5000"
                                    "--format" "edn"])))
      (is (= 1 @exit)))))
