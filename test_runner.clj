#!/usr/bin/env bb

(ns test-runner
  (:require [clojure.test :as test]))

(def test-namespaces
  '[babacrap.integration-test
    babacrap.detangle-test])

(doseq [ns-sym test-namespaces]
  (require ns-sym))

(let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
  (System/exit (+ fail error)))
