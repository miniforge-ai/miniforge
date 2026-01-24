(ns ai.miniforge.agent.messaging-test
  "Tests for inter-agent messaging protocol.

   Validates:
   - Message creation and validation
   - Message routing and delivery
   - Message protocol operations
   - Event emission
   - Schema compliance"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.agent.protocols.impl.messaging :as msg-impl]))

;------------------------------------------------------------------------------ Layer 0
;; Test Fixtures

(def test-workflow-id (random-uuid))
(def test-planner-id (random-uuid))
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
                   test-planner-id
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

;------------------------------------------------------------------------------ Layer 3
;; Message Router Tests

(deftest test-message-router-creation
  (testing "Create message router"
    (let [router (agent/create-message-router)]
      (is (some? router))
      (is (satisfies? agent/MessageRouter router)))))

(deftest test-route-message
  (testing "Route message to recipient"
    (let [router (agent/create-message-router)
          message (msg-impl/create-message
                   {:type :clarification-request
                    :from-agent :implementer
                    :from-instance-id test-implementer-id
                    :to-agent :planner
                    :workflow-id test-workflow-id
                    :content "Question?"})
          routed (agent/route-message router message)]
      (is (some? routed))
      (is (= :delivered (:message/status routed)))
      (is (inst? (:message/routed-at routed))))))

(deftest test-get-messages-for-agent
  (testing "Get messages for specific agent"
    (let [router (agent/create-message-router)
          msg1 (msg-impl/create-message
                {:type :concern
                 :from-agent :reviewer
                 :from-instance-id (random-uuid)
                 :to-agent :implementer
                 :workflow-id test-workflow-id
                 :content "Concern 1"})
          msg2 (msg-impl/create-message
                {:type :suggestion
                 :from-agent :tester
                 :from-instance-id (random-uuid)
                 :to-agent :implementer
                 :workflow-id test-workflow-id
                 :content "Suggestion 1"})
          msg3 (msg-impl/create-message
                {:type :clarification-request
                 :from-agent :implementer
                 :from-instance-id test-implementer-id
                 :to-agent :planner
                 :workflow-id test-workflow-id
                 :content "Question?"})]
      ;; Route all messages
      (agent/route-message router msg1)
      (agent/route-message router msg2)
      (agent/route-message router msg3)

      ;; Check implementer's inbox
      (let [implementer-msgs (agent/get-messages-for-agent
                              router :implementer test-workflow-id)]
        (is (= 2 (count implementer-msgs)))
        (is (every? #(= :implementer (:message/to-agent %))
                    implementer-msgs)))

      ;; Check planner's inbox
      (let [planner-msgs (agent/get-messages-for-agent
                          router :planner test-workflow-id)]
        (is (= 1 (count planner-msgs)))
        (is (= :planner (:message/to-agent (first planner-msgs))))))))

(deftest test-get-messages-by-workflow
  (testing "Get all messages in workflow ordered by timestamp"
    (let [router (agent/create-message-router)
          msg1 (msg-impl/create-message
                {:type :concern
                 :from-agent :reviewer
                 :from-instance-id (random-uuid)
                 :to-agent :implementer
                 :workflow-id test-workflow-id
                 :content "Msg 1"})
          msg2 (msg-impl/create-message
                {:type :suggestion
                 :from-agent :tester
                 :from-instance-id (random-uuid)
                 :to-agent :implementer
                 :workflow-id test-workflow-id
                 :content "Msg 2"})]
      (agent/route-message router msg1)
      (Thread/sleep 10) ;; Ensure different timestamps
      (agent/route-message router msg2)

      (let [all-msgs (agent/get-messages-by-workflow router test-workflow-id)]
        (is (= 2 (count all-msgs)))
        ;; Check ordering by timestamp
        (is (= "Msg 1" (:message/content (first all-msgs))))
        (is (= "Msg 2" (:message/content (second all-msgs))))))))

(deftest test-clear-workflow-messages
  (testing "Clear all messages for workflow"
    (let [router (agent/create-message-router)
          msg (msg-impl/create-message
               {:type :concern
                :from-agent :reviewer
                :from-instance-id (random-uuid)
                :to-agent :implementer
                :workflow-id test-workflow-id
                :content "Test"})]
      (agent/route-message router msg)
      (is (= 1 (count (agent/get-messages-by-workflow router test-workflow-id))))

      (agent/clear-workflow-messages router test-workflow-id)
      (is (empty? (agent/get-messages-by-workflow router test-workflow-id)))
      (is (empty? (agent/get-messages-for-agent router :implementer test-workflow-id))))))

;------------------------------------------------------------------------------ Layer 4
;; AgentMessaging Protocol Tests

(deftest test-agent-messaging-creation
  (testing "Create agent messaging capability"
    (let [router (agent/create-message-router)
          messaging (agent/create-agent-messaging
                     :implementer
                     test-implementer-id
                     test-workflow-id
                     router)]
      (is (some? messaging))
      (is (satisfies? agent/InterAgentMessaging messaging)))))

(deftest test-send-message
  (testing "Send message from agent"
    (let [router (agent/create-message-router)
          messaging (agent/create-agent-messaging
                     :implementer
                     test-implementer-id
                     test-workflow-id
                     router)
          result (agent/send-message
                  messaging
                  {:type :clarification-request
                   :to-agent :planner
                   :content "Need help with X"})]
      (is (some? (:message result)))
      (is (some? (:event result)))
      (is (= :implementer (:message/from-agent (:message result))))
      (is (= test-implementer-id (:message/from-instance-id (:message result))))
      (is (= :planner (:message/to-agent (:message result))))
      (is (= test-workflow-id (:message/workflow-id (:message result))))

      ;; Check event structure
      (let [event (:event result)]
        (is (= :agent/message-sent (:event/type event)))
        (is (= :implementer (:from-agent/id event)))
        (is (= :planner (:to-agent/id event)))
        (is (= :clarification-request (:message-type event)))))))

(deftest test-receive-messages
  (testing "Receive messages sent to agent"
    (let [router (agent/create-message-router)
          sender (agent/create-agent-messaging
                  :reviewer
                  (random-uuid)
                  test-workflow-id
                  router)
          receiver (agent/create-agent-messaging
                    :implementer
                    test-implementer-id
                    test-workflow-id
                    router)]
      ;; Send message to implementer
      (agent/send-message sender
                          {:type :concern
                           :to-agent :implementer
                           :content "Policy issue"})

      ;; Check implementer received it
      (let [received (agent/receive-messages receiver)]
        (is (= 1 (count received)))
        (is (= :concern (:message/type (first received))))
        (is (= "Policy issue" (:message/content (first received))))))))

(deftest test-respond-to-message
  (testing "Send response to received message"
    (let [router (agent/create-message-router)
          implementer-msg (agent/create-agent-messaging
                           :implementer
                           test-implementer-id
                           test-workflow-id
                           router)
          planner-msg (agent/create-agent-messaging
                       :planner
                       test-planner-id
                       test-workflow-id
                       router)]
      ;; Implementer sends clarification request
      (let [request-result (agent/send-clarification-request
                            implementer-msg
                            :planner
                            "How should we handle X?")
            original-msg (:message request-result)]

        ;; Planner responds
        (let [response-result (agent/respond-to-message
                               planner-msg
                               original-msg
                               "Handle X by doing Y")]
          (is (some? (:message response-result)))
          (is (= :clarification-response (:message/type (:message response-result))))
          (is (= (:message/id original-msg)
                 (:message/parent-id (:message response-result))))
          (is (= "Handle X by doing Y"
                 (:message/content (:message response-result)))))

        ;; Implementer receives response
        (let [received (agent/receive-messages implementer-msg)]
          (is (= 1 (count received)))
          (is (= :clarification-response (:message/type (first received)))))))))

;------------------------------------------------------------------------------ Layer 5
;; Convenience Function Tests

(deftest test-send-clarification-request
  (testing "Send clarification request convenience function"
    (let [router (agent/create-message-router)
          messaging (agent/create-agent-messaging
                     :implementer
                     test-implementer-id
                     test-workflow-id
                     router)
          result (agent/send-clarification-request
                  messaging
                  :planner
                  "Question?")]
      (is (= :clarification-request (:message/type (:message result))))
      (is (= :planner (:message/to-agent (:message result)))))))

(deftest test-send-concern
  (testing "Send concern convenience function"
    (let [router (agent/create-message-router)
          messaging (agent/create-agent-messaging
                     :reviewer
                     (random-uuid)
                     test-workflow-id
                     router)
          result (agent/send-concern
                  messaging
                  :implementer
                  "Security issue")]
      (is (= :concern (:message/type (:message result))))
      (is (= :implementer (:message/to-agent (:message result)))))))

(deftest test-send-suggestion
  (testing "Send suggestion convenience function"
    (let [router (agent/create-message-router)
          messaging (agent/create-agent-messaging
                     :tester
                     (random-uuid)
                     test-workflow-id
                     router)
          result (agent/send-suggestion
                  messaging
                  :implementer
                  "Add more tests")]
      (is (= :suggestion (:message/type (:message result))))
      (is (= :implementer (:message/to-agent (:message result)))))))

(deftest test-get-clarification-requests
  (testing "Filter clarification requests"
    (let [router (agent/create-message-router)
          sender (agent/create-agent-messaging
                  :implementer
                  test-implementer-id
                  test-workflow-id
                  router)
          receiver (agent/create-agent-messaging
                    :planner
                    test-planner-id
                    test-workflow-id
                    router)]
      (agent/send-clarification-request sender :planner "Q1?")
      (agent/send-concern sender :planner "Concern")
      (agent/send-clarification-request sender :planner "Q2?")

      (let [clarifications (agent/get-clarification-requests receiver)]
        (is (= 2 (count clarifications)))
        (is (every? #(= :clarification-request (:message/type %))
                    clarifications))))))

(deftest test-get-concerns
  (testing "Filter concerns"
    (let [router (agent/create-message-router)
          sender (agent/create-agent-messaging
                  :reviewer
                  (random-uuid)
                  test-workflow-id
                  router)
          receiver (agent/create-agent-messaging
                    :implementer
                    test-implementer-id
                    test-workflow-id
                    router)]
      (agent/send-concern sender :implementer "C1")
      (agent/send-suggestion sender :implementer "S1")
      (agent/send-concern sender :implementer "C2")

      (let [concerns (agent/get-concerns receiver)]
        (is (= 2 (count concerns)))
        (is (every? #(= :concern (:message/type %)) concerns))))))

(deftest test-get-suggestions
  (testing "Filter suggestions"
    (let [router (agent/create-message-router)
          sender (agent/create-agent-messaging
                  :tester
                  (random-uuid)
                  test-workflow-id
                  router)
          receiver (agent/create-agent-messaging
                    :implementer
                    test-implementer-id
                    test-workflow-id
                    router)]
      (agent/send-suggestion sender :implementer "S1")
      (agent/send-concern sender :implementer "C1")
      (agent/send-suggestion sender :implementer "S2")

      (let [suggestions (agent/get-suggestions receiver)]
        (is (= 2 (count suggestions)))
        (is (every? #(= :suggestion (:message/type %)) suggestions))))))

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
