(ns ai.miniforge.agent.messaging-creation-test
  "Tests for message creation and validation (Layers 0-2)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.protocols.impl.messaging :as msg-impl]))

;------------------------------------------------------------------------------ Layer 0
;; Test Fixtures

(def test-workflow-id (random-uuid))
(def test-implementer-id (random-uuid))

;------------------------------------------------------------------------------ Layer 1
;; Message Creation Tests

(deftest test-create-message
  (testing "Create message with all required fields"
    (let [message (msg-impl/create-message
                   {:type :clarification-request
                    :from-agent :implementer
                    :from-instance-id test-implementer-id
                    :to-agent :planner
                    :workflow-id test-workflow-id
                    :content "Should we use new security group?"})]
      (is (uuid? (:message/id message)))
      (is (= :clarification-request (:message/type message)))
      (is (= :implementer (:message/from-agent message)))
      (is (= test-implementer-id (:message/from-instance-id message)))
      (is (= :planner (:message/to-agent message)))
      (is (= test-workflow-id (:message/workflow-id message)))
      (is (= "Should we use new security group?" (:message/content message)))
      (is (inst? (:message/timestamp message))))))

(deftest test-create-clarification-request
  (testing "Create clarification request message"
    (let [message (msg-impl/create-clarification-request
                   :implementer
                   test-implementer-id
                   :planner
                   test-workflow-id
                   "Need clarification on X")]
      (is (= :clarification-request (:message/type message)))
      (is (= :implementer (:message/from-agent message)))
      (is (= :planner (:message/to-agent message))))))

(deftest test-create-concern
  (testing "Create concern message"
    (let [message (msg-impl/create-concern
                   :reviewer
                   (random-uuid)
                   :implementer
                   test-workflow-id
                   "Policy violation detected")]
      (is (= :concern (:message/type message)))
      (is (= :reviewer (:message/from-agent message)))
      (is (= :implementer (:message/to-agent message))))))

(deftest test-create-suggestion
  (testing "Create suggestion message"
    (let [message (msg-impl/create-suggestion
                   :tester
                   (random-uuid)
                   :implementer
                   test-workflow-id
                   "Consider adding error handling")]
      (is (= :suggestion (:message/type message)))
      (is (= :tester (:message/from-agent message)))
      (is (= :implementer (:message/to-agent message))))))

(deftest test-create-clarification-response
  (testing "Create clarification response with parent-id"
    (let [parent-id (random-uuid)
          message (msg-impl/create-clarification-response
                   :planner
                   (random-uuid)
                   :implementer
                   test-workflow-id
                   "Yes, create new security group"
                   parent-id)]
      (is (= :clarification-response (:message/type message)))
      (is (= parent-id (:message/parent-id message))))))

;------------------------------------------------------------------------------ Layer 2
;; Message Validation Tests

(deftest test-validate-valid-message
  (testing "Validate well-formed message"
    (let [message (msg-impl/create-message
                   {:type :concern
                    :from-agent :reviewer
                    :from-instance-id (random-uuid)
                    :to-agent :implementer
                    :workflow-id test-workflow-id
                    :content "Issue found"})
          result (msg-impl/validate-message-impl message)]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest test-validate-invalid-message-type
  (testing "Reject message with invalid type"
    (let [message {:message/id (random-uuid)
                   :message/type :invalid-type
                   :message/from-agent :planner
                   :message/from-instance-id (random-uuid)
                   :message/to-agent :implementer
                   :message/workflow-id test-workflow-id
                   :message/content "Test"
                   :message/timestamp (java.util.Date.)}
          result (msg-impl/validate-message-impl message)]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))

(deftest test-validate-missing-required-field
  (testing "Reject message missing required field"
    (let [message {:message/id (random-uuid)
                   :message/type :concern
                   :message/from-agent :reviewer
                   ;; Missing :message/from-instance-id
                   :message/to-agent :implementer
                   :message/workflow-id test-workflow-id
                   :message/content "Test"}
          result (msg-impl/validate-message-impl message)]
      (is (not (:valid? result))))))

(deftest test-valid-message-predicate
  (testing "Check valid-message? predicate"
    (let [valid-msg (msg-impl/create-message
                     {:type :suggestion
                      :from-agent :tester
                      :from-instance-id (random-uuid)
                      :to-agent :implementer
                      :workflow-id test-workflow-id
                      :content "Suggestion"})]
      (is (msg-impl/valid-message? valid-msg)))))
