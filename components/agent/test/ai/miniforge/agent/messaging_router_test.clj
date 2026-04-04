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

(ns ai.miniforge.agent.messaging-router-test
  "Tests for message routing (Layer 3)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.agent.protocols.impl.messaging :as msg-impl]))

;------------------------------------------------------------------------------ Layer 0
;; Test Fixtures

(def test-workflow-id (random-uuid))
(def test-implementer-id (random-uuid))

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
