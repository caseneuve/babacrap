(ns demo.core-test
  (:require [clojure.test :refer [deftest is]]
            [demo.core :as sut]))

(deftest simple-test
  (is (= :yes (sut/simple true))))

(deftest partial-test
  (is (= 1 (sut/partially-covered :a))))
