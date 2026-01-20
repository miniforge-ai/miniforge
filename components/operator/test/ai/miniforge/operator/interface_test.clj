(ns ai.miniforge.operator.interface-test
  "Tests for the operator (meta-agent) component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.operator.interface :as op]
   [ai.miniforge.operator.protocol :as proto]))

;; ============================================================================
;; Operator creation tests
;; ============================================================================

(deftest create-operator-test
  (testing "create-operator returns an operator"
    (let [operator (op/create-operator)]
      (is (some? operator))
      (is (satisfies? proto/Operator operator)))))

(deftest create-pattern-detector-test
  (testing "create-pattern-detector returns a detector"
    (let [detector (op/create-pattern-detector)]
      (is (some? detector))
      (is (satisfies? proto/PatternDetector detector)))))

(deftest create-improvement-generator-test
  (testing "create-improvement-generator returns a generator"
    (let [generator (op/create-improvement-generator)]
      (is (some? generator))
      (is (satisfies? proto/ImprovementGenerator generator)))))

(deftest create-governance-test
  (testing "create-governance returns a governance manager"
    (let [governance (op/create-governance)]
      (is (some? governance))
      (is (satisfies? proto/Governance governance)))))

;; ============================================================================
;; Signal observation tests
;; ============================================================================

(deftest observe-signal-test
  (testing "observe-signal records a signal"
    (let [operator (op/create-operator)
          signal {:type :workflow-failed
                  :data {:phase :implement :error "Test error"}}
          recorded (op/observe-signal operator signal)]
      (is (some? (:signal/id recorded)))
      (is (some? (:signal/timestamp recorded)))
      (is (= :workflow-failed (:signal/type recorded))))))

(deftest get-signals-test
  (testing "get-signals returns recorded signals"
    (let [operator (op/create-operator)]
      ;; Record some signals
      (op/observe-signal operator {:type :workflow-failed :data {:phase :implement}})
      (op/observe-signal operator {:type :workflow-complete :data {:workflow-id (random-uuid)}})
      (op/observe-signal operator {:type :workflow-failed :data {:phase :verify}})

      (testing "all signals"
        (let [signals (op/get-signals operator {})]
          (is (= 3 (count signals)))))

      (testing "filtered by type"
        (let [signals (op/get-signals operator {:type :workflow-failed})]
          (is (= 2 (count signals)))))

      (testing "limited"
        (let [signals (op/get-signals operator {:limit 2})]
          (is (= 2 (count signals))))))))

;; ============================================================================
;; Pattern detection tests
;; ============================================================================

(deftest detect-patterns-test
  (testing "detect-patterns finds patterns"
    (let [detector (op/create-pattern-detector)
          ;; Create signals that form a pattern (3+ repeated failures)
          signals (for [i (range 4)]
                    {:signal/id (random-uuid)
                     :signal/type :workflow-failed
                     :signal/data {:phase :implement :error (str "Error " i)}
                     :signal/timestamp (System/currentTimeMillis)})
          patterns (op/detect-patterns detector signals)]
      (is (seq patterns))
      (is (some #(= :repeated-phase-failure (:pattern/type %)) patterns)))))

(deftest analyze-patterns-test
  (testing "analyze-patterns returns patterns and recommendations"
    (let [operator (op/create-operator)]
      ;; Create failure signals to trigger pattern detection
      (dotimes [_ 4]
        (op/observe-signal operator {:type :workflow-failed
                                      :data {:phase :implement}}))

      (let [result (op/analyze-patterns operator (* 60 60 1000))]
        (is (map? result))
        (is (contains? result :patterns))
        (is (contains? result :recommendations))))))

;; ============================================================================
;; Improvement management tests
;; ============================================================================

(deftest propose-improvement-test
  (testing "propose-improvement creates a proposal"
    (let [operator (op/create-operator)
          improvement {:type :rule-addition
                       :target :knowledge-base
                       :change {:action :add-rule}
                       :rationale "Prevent future errors"}
          result (op/propose-improvement operator improvement)]
      (is (uuid? (:proposal-id result)))
      (is (= :proposed (:status result))))))

(deftest get-proposals-test
  (testing "get-proposals returns proposals"
    (let [operator (op/create-operator)]
      (op/propose-improvement operator {:type :rule-addition :target :test :rationale "Test"})
      (op/propose-improvement operator {:type :gate-adjustment :target :test :rationale "Test"})

      (testing "all proposals"
        (let [proposals (op/get-proposals operator {})]
          (is (= 2 (count proposals)))))

      (testing "filtered by type"
        (let [proposals (op/get-proposals operator {:type :rule-addition})]
          (is (= 1 (count proposals))))))))

(deftest apply-improvement-test
  (testing "apply-improvement updates proposal status"
    (let [operator (op/create-operator)
          {:keys [proposal-id]} (op/propose-improvement operator
                                                         {:type :rule-addition
                                                          :target :test
                                                          :rationale "Test"})
          result (op/apply-improvement operator proposal-id)]
      (is (:success? result))
      (is (= :applied (get-in result [:applied :improvement/status]))))))

(deftest reject-improvement-test
  (testing "reject-improvement updates proposal with reason"
    (let [operator (op/create-operator)
          {:keys [proposal-id]} (op/propose-improvement operator
                                                         {:type :rule-addition
                                                          :target :test
                                                          :rationale "Test"})
          rejected (op/reject-improvement operator proposal-id "Not applicable")]
      (is (= :rejected (:improvement/status rejected)))
      (is (= "Not applicable" (:improvement/rejection-reason rejected))))))

;; ============================================================================
;; Governance tests
;; ============================================================================

(deftest governance-requires-approval-test
  (testing "governance correctly identifies approval requirements"
    (let [governance (op/create-governance)]

      (testing "workflow modifications require approval"
        (let [improvement {:improvement/type :workflow-modification
                           :improvement/confidence 0.9}]
          (is (true? (op/requires-approval? governance improvement)))))

      (testing "high-confidence gate adjustments don't require approval"
        (let [improvement {:improvement/type :gate-adjustment
                           :improvement/confidence 0.99}]
          (is (false? (op/requires-approval? governance improvement)))))

      (testing "low-confidence improvements require approval"
        (let [improvement {:improvement/type :rule-addition
                           :improvement/confidence 0.5}]
          (is (true? (op/requires-approval? governance improvement))))))))

(deftest get-approval-policy-test
  (testing "approval policies are defined for improvement types"
    (let [governance (op/create-governance)]

      (let [policy (op/get-approval-policy governance :rule-addition)]
        (is (true? (:auto-approve? policy)))
        (is (number? (:required-confidence policy))))

      (let [policy (op/get-approval-policy governance :workflow-modification)]
        (is (false? (:auto-approve? policy)))))))

;; ============================================================================
;; Type definitions tests
;; ============================================================================

(deftest signal-types-test
  (testing "signal types are defined"
    (is (set? op/signal-types))
    (is (contains? op/signal-types :workflow-complete))
    (is (contains? op/signal-types :workflow-failed))
    (is (contains? op/signal-types :phase-rollback))))

(deftest improvement-types-test
  (testing "improvement types are defined"
    (is (set? op/improvement-types))
    (is (contains? op/improvement-types :prompt-change))
    (is (contains? op/improvement-types :rule-addition))
    (is (contains? op/improvement-types :workflow-modification))))
