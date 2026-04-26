(ns babacrap.detangle-test
  (:require [babacrap.detangle :as detangle]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def fixture-src "test/fixtures/detangle/src")

(defn findings []
  (detangle/analyze-paths [fixture-src]))

(defn finding-by-rule [rule]
  (some #(when (= rule (:rule %)) %) (findings)))

(defn finding-by-var-and-rule [var-sym rule]
  (some #(when (and (= var-sym (:var %))
                    (= rule (:rule %)))
           %)
        (findings)))

(deftest detangle-rule-detection-test
  (testing "hidden context calls are reported as explicit dependency questions"
    (let [finding (finding-by-rule :context/hidden-time-or-randomness)]
      (is (= 'demo.detangle/expired? (:var finding)))
      (is (= {:call 'System/currentTimeMillis} (:evidence finding)))
      (is (str/includes? (:question finding) "passed explicitly"))))

  (testing "case on dispatch data is reported without flagging ordinary literal cases"
    (let [dispatch (finding-by-var-and-rule 'demo.detangle/handle-event!
                                            :dispatch/data-dispatch)]
      (is (= '{:form case
               :dispatch-expr (:type event)
               :dispatch-key :type
               :dispatch-target event
               :branches 3}
             (:evidence dispatch)))
      (is (not-any? #(and (= :dispatch/data-dispatch (:rule %))
                          (= 'demo.detangle/literal-case (:var %)))
                    (findings)))))

  (testing "cond equality tests over the same dispatch data are reported"
    (let [dispatch (finding-by-var-and-rule 'demo.detangle/route-event!
                                            :dispatch/data-dispatch)]
      (is (= '{:form cond
               :dispatch-expr (:kind event)
               :dispatch-key :kind
               :dispatch-target event
               :branches 2
               :values [:created :deleted]}
             (:evidence dispatch)))))

  (testing "repeated instance? checks over one subject are reported as type branching"
    (let [branching (finding-by-rule :dispatch/type-branching)]
      (is (= 'demo.detangle/subtotal (:var branching)))
      (is (= {:subject 'item
              :types ['Product 'Subscription 'Bundle]}
             (:evidence branching)))))

  (testing "deep get-in paths in predicate-like rules are reported as raw shape coupling"
    (let [raw-shape (finding-by-rule :data/raw-shape-in-rule)]
      (is (= 'demo.detangle/eligible? (:var raw-shape)))
      (is (= {:root 'row
              :paths [[:account :status]
                      [:billing :last_12_months_cents]]}
             (:evidence raw-shape)))))

  (testing "local atoms used for accumulation are reported as local identity"
    (let [reader-deref (finding-by-var-and-rule 'demo.detangle/collect-active
                                                :state/local-mutable-accumulation)
          explicit-deref (finding-by-var-and-rule 'demo.detangle/collect-active-explicit-deref
                                                  :state/local-mutable-accumulation)]
      (is (= {:local 'result
              :ops ['swap! 'deref]}
             (:evidence reader-deref)))
      (is (= {:local 'result
              :ops ['swap! 'deref]}
             (:evidence explicit-deref))))))

(deftest detangle-reporting-test
  (testing "EDN output is parseable and summarizes findings"
    (let [{:keys [exit out]} (detangle/run-result ["--src" fixture-src "--format" "edn"])
          parsed (edn/read-string out)]
      (is (zero? exit))
      (is (= 7 (get-in parsed [:summary :total])))
      (is (= 18 (get-in parsed [:summary :severity-sum])))
      (is (= 7 (count (:findings parsed))))))

  (testing "text output keeps findings human-oriented"
    (let [{:keys [exit out]} (detangle/run-result ["--src" fixture-src])]
      (is (zero? exit))
      (is (str/includes? out "Detangle analysis: 7 findings"))
      (is (str/includes? out ":dispatch/data-dispatch"))
      (is (str/includes? out "Can data-based dispatch"))))

  (testing "invalid formats return usage errors"
    (let [{:keys [exit err]} (detangle/run-result ["--format" "json"])]
      (is (= 2 exit))
      (is (str/includes? err "Must be text or edn")))))
