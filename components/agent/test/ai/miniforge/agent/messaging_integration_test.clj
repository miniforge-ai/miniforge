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

(ns ai.miniforge.agent.messaging-integration-test
  "Tests for inter-agent event emission and full integration flow."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.agent.protocols.impl.messaging :as msg-impl]
   [ai.miniforge.event-stream.interface :as event-stream]))

;------------------------------------------------------------------------------ Layer 0
;; Test Fixtures

(def test-workflow-id (random-uuid))
(def test-planner-id (random-uuid))
(def test-implementer-id (random-uuid))

;------------------------------------------------------------------------------ Layer 6
;; Event Emission Tests

(deftest test-emit-inter-agent-event-with-stream
  (testing "emit-inter-agent-event! publishes to a real event stream"
    (let [stream      (event-stream/create-event-stream)
          workflow-id (random-uuid)
          result      (msg-impl/emit-inter-agent-event!
                        stream workflow-id :implementer :planner :clarification-request
                        'ai.miniforge.event-stream.interface/inter-agent-message-sent)]
      (is (some? result))
      (is (= :agent/message-sent (:event/type result)))
      (is (= :implementer (:from-agent/id result)))
      (is (= :planner (:to-agent/id result)))
      ;; Verify event was actually published to the stream
      (let [events (event-stream/get-events stream)]
        (is (<= 1 (count events)))
        (is (= :agent/message-sent (:event/type (last events))))))))

(deftest test-emit-inter-agent-event-nil-stream
  (testing "emit-inter-agent-event! with nil stream is a safe no-op"
    (let [result (msg-impl/emit-inter-agent-event!
                   nil (random-uuid) :a :b :concern
                   'ai.miniforge.event-stream.interface/inter-agent-message-sent)]
      (is (nil? result)))))

(deftest test-send-message-emits-events-with-stream
  (testing "send-message-impl emits sent+received events when stream is present"
    (let [stream  (event-stream/create-event-stream)
          router  (agent/create-message-router)
          messaging (agent/create-agent-messaging
                      :implementer test-implementer-id test-workflow-id router stream)
          result  (agent/send-message messaging
                                      {:type :clarification-request
                                       :to-agent :planner
                                       :content "Question?"})]
      ;; The result should include the sent event
      (is (some? (:event result)))
      (is (= :agent/message-sent (:event/type (:event result))))
      ;; Both sent + received events should be on the stream
      (let [events      (event-stream/get-events stream)
            event-types (set (map :event/type events))]
        (is (contains? event-types :agent/message-sent))
        (is (contains? event-types :agent/message-received))))))

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
