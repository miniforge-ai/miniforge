;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.agent.messaging-protocol-test
  "Tests for AgentMessaging protocol and convenience functions (Layers 4-5)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.interface :as agent]))

;------------------------------------------------------------------------------ Layer 0
;; Test Fixtures

(def test-workflow-id (random-uuid))
(def test-planner-id (random-uuid))
(def test-implementer-id (random-uuid))

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
                       router)
          ;; Implementer sends clarification request
          request-result (agent/send-clarification-request
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
          (is (= :clarification-response (:message/type (first received))))))))

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
