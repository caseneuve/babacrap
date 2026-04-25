#!/usr/bin/env bb

(ns test-runner
  (:require [babacrap.integration-test]
            [clojure.test :as test]))

(let [{:keys [fail error]} (test/run-tests 'babacrap.integration-test)]
  (when (pos? (+ fail error))
    (System/exit 1))
  (println "ok"))
