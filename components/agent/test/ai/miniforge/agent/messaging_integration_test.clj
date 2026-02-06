(ns ai.miniforge.agent.messaging-integration-test
  "Tests for message events and full integration flow (Layers 6-7)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.agent.protocols.impl.messaging :as msg-impl]))

;------------------------------------------------------------------------------ Layer 0
;; Test Fixtures

(def test-workflow-id (random-uuid))
(def test-planner-id (random-uuid))
(def test-implementer-id (random-uuid))

;------------------------------------------------------------------------------ Layer 6
;; Event Emission Tests

(deftest test-message-sent-event-structure
  (testing "Message sent event conforms to N3 spec"
    (let [message (msg-impl/create-message
                   {:type :clarification-request
                    :from-agent :implementer
                    :from-instance-id test-implementer-id
                    :to-agent :planner
                    :workflow-id test-workflow-id
                    :content "Question"})
          event (msg-impl/create-message-sent-event message)]
      ;; Check required N3 event fields
      (is (= :agent/message-sent (:event/type event)))
      (is (uuid? (:event/id event)))
      (is (inst? (:event/timestamp event)))
      (is (= "1.0.0" (:event/version event)))

      ;; Check N3 section 3.7 specific fields
      (is (= :implementer (:from-agent/id event)))
      (is (= test-implementer-id (:from-agent/instance-id event)))
      (is (= :planner (:to-agent/id event)))
      (is (= test-workflow-id (:workflow/id event)))
      (is (= :clarification-request (:message-type event)))
      (is (= "Question" (:message-content event)))
      (is (string? (:message event))))))

(deftest test-message-received-event-structure
  (testing "Message received event conforms to N3 spec"
    (let [message (msg-impl/create-message
                   {:type :clarification-response
                    :from-agent :planner
                    :from-instance-id test-planner-id
                    :to-agent :implementer
                    :workflow-id test-workflow-id
                    :content "Answer"})
          event (msg-impl/create-message-received-event message)]
      ;; Check required N3 event fields
      (is (= :agent/message-received (:event/type event)))
      (is (uuid? (:event/id event)))
      (is (inst? (:event/timestamp event)))
      (is (= "1.0.0" (:event/version event)))

      ;; Check N3 section 3.7 specific fields
      (is (= :planner (:from-agent/id event)))
      (is (= :implementer (:to-agent/id event)))
      (is (= test-workflow-id (:workflow/id event)))
      (is (= :clarification-response (:message-type event)))
      (is (= "Answer" (:message-content event)))
      (is (string? (:message event))))))

;------------------------------------------------------------------------------ Layer 7
;; Integration Tests

(deftest test-full-message-flow
  (testing "Complete message exchange between agents"
    (let [router (agent/create-message-router)
          workflow-id (random-uuid)

          ;; Create messaging for multiple agents
          implementer (agent/create-agent-messaging
                       :implementer (random-uuid) workflow-id router)
          planner (agent/create-agent-messaging
                  :planner (random-uuid) workflow-id router)
          reviewer (agent/create-agent-messaging
                   :reviewer (random-uuid) workflow-id router)
          tester (agent/create-agent-messaging
                 :tester (random-uuid) workflow-id router)]

      ;; 1. Implementer asks planner for clarification
      (agent/send-clarification-request
       implementer :planner "Should we use new or existing resource?")

      ;; 2. Planner responds
      (let [planner-inbox (agent/receive-messages planner)
            request (first planner-inbox)]
        (is (= 1 (count planner-inbox)))
        (agent/respond-to-message planner request "Use existing resource"))

      ;; 3. Reviewer raises concern
      (agent/send-concern reviewer :implementer "Missing required tags")

      ;; 4. Tester makes suggestion
      (agent/send-suggestion tester :implementer "Add edge case tests")

      ;; 5. Check implementer's inbox
      ;; Should have: 1 clarification-response, 1 concern, 1 suggestion
      (let [inbox (agent/receive-messages implementer)]
        (is (= 3 (count inbox)))
        ;; Filter by message types received (not sent)
        (is (= 1 (count (filter #(= :clarification-response (:message/type %)) inbox))))
        (is (= 1 (count (agent/get-concerns implementer))))
        (is (= 1 (count (agent/get-suggestions implementer)))))

      ;; 6. Check workflow-wide messages
      (let [all-msgs (agent/get-messages-by-workflow router workflow-id)]
        (is (= 4 (count all-msgs)))
        ;; Verify chronological ordering
        (is (every? inst? (map :message/timestamp all-msgs)))))))
