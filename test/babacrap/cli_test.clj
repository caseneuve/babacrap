(ns babacrap.cli-test
  (:require [babacrap.cli :as cli]
            [babacrap.core :as crap]
            [babacrap.detangle :as detangle]
            [babacrap.mutation :as mutation]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest top-level-help-test
  (testing "no args prints top-level help and does not dispatch"
    (with-redefs [crap/run-result (fn [_] (throw (ex-info "should not run crap" {})))
                  mutation/run-result (fn [_] (throw (ex-info "should not run mutate" {})))
                  detangle/run-result (fn [_] (throw (ex-info "should not run detangle" {})))]
      (let [{:keys [exit out err]} (cli/run-result [])]
        (is (zero? exit))
        (is (str/includes? out "Usage:"))
        (is (str/includes? out "crap"))
        (is (str/includes? out "mutate"))
        (is (str/includes? out "detangle"))
        (is (nil? err)))))
  (testing "help flags and help alias print top-level help"
    (doseq [args [["--help"] ["-h"] ["help"]]]
      (let [{:keys [exit out err]} (cli/run-result args)]
        (is (zero? exit))
        (is (str/includes? out "Usage:"))
        (is (nil? err))))))

(deftest unknown-subcommand-test
  (let [{:keys [exit err]} (cli/run-result ["wat"])]
    (is (= 2 exit))
    (is (str/includes? err "Unknown subcommand: wat"))
    (is (str/includes? err "Usage:"))))

(deftest subcommand-dispatch-test
  (testing "dispatches to existing command run-result functions"
    (with-redefs [crap/run-result (fn [args] {:exit 0 :out (pr-str [:crap args])})
                  mutation/run-result (fn [args] {:exit 0 :out (pr-str [:mutate args])})
                  detangle/run-result (fn [args] {:exit 0 :out (pr-str [:detangle args])})]
      (is (= "[:crap [\"--format\" \"edn\"]]"
             (:out (cli/run-result ["crap" "--format" "edn"]))))
      (is (= "[:mutate [\"--limit\" \"1\"]]"
             (:out (cli/run-result ["mutate" "--limit" "1"]))))
      (is (= "[:detangle [\"--src\" \"test/fixtures/src\" \"--format\" \"edn\"]]"
             (:out (cli/run-result ["detangle" "--src" "test/fixtures/src" "--format" "edn"]))))))
  (testing "preserves underlying exit codes"
    (with-redefs [detangle/run-result (fn [_] {:exit 7 :err "boom"})]
      (is (= {:exit 7 :err "boom"}
             (cli/run-result ["detangle" "--format" "json"]))))))

(deftest subcommand-help-test
  (testing "subcommand help includes examples and does not analyze"
    (with-redefs [crap/analyze (fn [_] (throw (ex-info "should not analyze" {})))
                  mutation/collect-mutants (fn [_] (throw (ex-info "should not mutate" {})))
                  detangle/analyze-paths (fn [_] (throw (ex-info "should not detangle" {})))]
      (doseq [args [["crap" "--help"]
                    ["mutate" "--help"]
                    ["detangle" "--help"]]]
        (let [{:keys [exit out err]} (cli/run-result args)]
          (is (zero? exit))
          (is (str/includes? out "Examples:"))
          (is (nil? err)))))))
