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

(ns ai.miniforge.operator.intervention-test
  "Tests for bounded intervention lifecycle helpers."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.operator.messages :as messages]
   [ai.miniforge.operator.interface :as op]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- intervention-request
  [intervention-type target-id & {:as extra}]
  (merge {:intervention/type intervention-type
          :intervention/target-id target-id
          :intervention/requested-by "operator@example.com"
          :intervention/request-source :tui}
         extra))

;------------------------------------------------------------------------------ Layer 1
;; Unit tests

(deftest create-intervention-defaults-target-type-and-state
  (testing "given a retry intervention → workflow target type and proposed state are inferred"
    (let [request (op/create-intervention
                   (intervention-request :retry (random-uuid)))]
      (is (= :workflow (:intervention/target-type request)))
      (is (= :proposed (:intervention/state request)))
      (is (uuid? (:intervention/id request)))
      (is (inst? (:intervention/requested-at request)))
      (is (inst? (:intervention/updated-at request))))))

(deftest risky-interventions-default-to-approval-required
  (testing "given a cancel intervention → approval is required by default"
    (let [request (op/create-intervention
                   (intervention-request :cancel (random-uuid)))]
      (is (true? (op/approval-required? :cancel)))
      (is (true? (:intervention/approval-required? request))))))

(deftest intervention-lifecycle-reaches-verified
  (testing "given an intervention through approve, dispatch, apply, verify → it ends in verified"
    (let [created (op/create-intervention
                   (intervention-request :pause (random-uuid)))
          approved (:intervention (op/approve-intervention created))
          dispatched (:intervention (op/dispatch-intervention approved))
          applied (:intervention (op/apply-intervention-result dispatched {:paused true}))
          verified (:intervention (op/verify-intervention applied {:visible-state :paused-by-supervisor}))]
      (is (= :approved (:intervention/state approved)))
      (is (= :dispatched (:intervention/state dispatched)))
      (is (= :applied (:intervention/state applied)))
      (is (= :verified (:intervention/state verified)))
      (is (= {:visible-state :paused-by-supervisor} (:intervention/outcome verified))))))

(deftest intervention-can-enter-pending-human
  (testing "given a risky intervention → the lifecycle supports an explicit pending-human step"
    (let [created (op/create-intervention
                   (intervention-request :request-human-review (random-uuid)))
          pending (:intervention (op/start-intervention-approval created))
          approved (:intervention (op/approve-intervention pending))]
      (is (= :pending-human (:intervention/state pending)))
      (is (= :approved (:intervention/state approved))))))

(deftest invalid-intervention-operations-return-errors
  (testing "given a terminal intervention → further transitions are rejected"
    (let [created (op/create-intervention
                   (intervention-request :waive (random-uuid)))
          rejected (:intervention (op/reject-intervention created "not allowed"))
          result (op/dispatch-intervention rejected)]
      (is (= :rejected (:intervention/state rejected)))
      (is (false? (:success? result)))
      (is (= :terminal-state (:error result)))
      (is (= (messages/t :intervention/terminal-state {:state :rejected})
             (:message result)))))

  (testing "given an unknown intervention type → creation throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          (re-pattern (java.util.regex.Pattern/quote
                                       (messages/t :intervention/unknown-type)))
                          (op/create-intervention
                           (intervention-request :unknown-action (random-uuid)))))))
