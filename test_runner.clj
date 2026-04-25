#!/usr/bin/env bb

(ns test-runner
  (:require [babacrap.core :as babacrap]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn run-clj-kondo [& args]
  (let [{:keys [exit out err]}
        (apply p/shell {:out :string :err :string :continue true}
               "clj-kondo" args)]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (println err)
        (println out))
      (throw (ex-info "clj-kondo failed" {:exit exit :err err :out out})))
    (edn/read-string out)))

(def hook-result
  (run-clj-kondo "--lint" "test/corpus"
                 "--cache" "false"
                 "--fail-level" "error"
                 "--config" "{:linters {:babacrap/cyclomatic-complexity {:level :warning :max 3}} :output {:format :edn}}"))

(def findings (:findings hook-result))
(def messages (map :message findings))

(when-not (= 2 (count findings))
  (throw (ex-info (str "Expected 2 complexity findings, got " (count findings))
                  {:findings findings})))

(when-not (some #(str/includes? % "`complicated` is 5") messages)
  (throw (ex-info "Expected complicated score finding" {:messages messages})))

(when-not (some #(str/includes? % "`complicated-multi-arity` is 4") messages)
  (throw (ex-info "Expected multi-arity score finding" {:messages messages})))

(fs/delete-tree "target/test-coverage")

(def crap-results
  (babacrap/analyze {:src-paths ["test/fixtures/src"]
                     :test-paths ["test/fixtures/test"]
                     :ns-regex ["demo.*"]
                     :test-ns-regex [".*-test"]
                     :output "target/test-coverage"
                     :coverage? true}))

(def by-name
  (into {} (map (juxt :name identity)) crap-results))

(doseq [name '[simple uncovered-complex partially-covered]]
  (when-not (contains? by-name name)
    (throw (ex-info (str "Missing CRAP result for " name)
                    {:results crap-results}))))

(when-not (= 5 (:complexity (by-name 'uncovered-complex)))
  (throw (ex-info "Expected uncovered-complex complexity 5"
                  {:result (by-name 'uncovered-complex)})))

(when-not (> (:crap (by-name 'uncovered-complex))
             (:crap (by-name 'simple)))
  (throw (ex-info "Expected uncovered-complex to have a higher CRAP score than simple"
                  {:results crap-results})))

(println "ok")
