#!/usr/bin/env bb

(ns test-runner
  (:require [babacrap.detangle-test]
            [babacrap.integration-test]
            [clojure.test :as test]))

(let [{:keys [fail error]} (test/run-tests 'babacrap.integration-test
                                           'babacrap.detangle-test)]
  (when (pos? (+ fail error))
    (System/exit 1))
  (println "ok"))
