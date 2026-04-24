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

(ns ai.miniforge.pr-lifecycle.fsm-test
  "Unit tests for the PR lifecycle controller FSM."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-lifecycle.messages :as messages]
   [ai.miniforge.pr-lifecycle.fsm :as sut]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Test helpers

(defn- transition-result
  "Apply a controller FSM transition and return the result map."
  [from-status to-status]
  (sut/transition from-status to-status))

;------------------------------------------------------------------------------ Layer 1
;; FSM validation

(deftest valid-transition-happy-path-test
  (testing "happy path transitions succeed through merge"
    (let [creating-pr (transition-result :pending :creating-pr)
          monitoring-ci (transition-result :creating-pr :monitoring-ci)
          monitoring-review (transition-result :monitoring-ci :monitoring-review)
          ready-to-merge (transition-result :monitoring-review :ready-to-merge)
          merged (transition-result :ready-to-merge :merged)]
      (is (schema/succeeded? creating-pr))
      (is (= {:state :creating-pr :event :create-pr} (:transition creating-pr)))
      (is (schema/succeeded? monitoring-ci))
      (is (= {:state :monitoring-ci :event :start-ci-monitoring} (:transition monitoring-ci)))
      (is (schema/succeeded? monitoring-review))
      (is (= {:state :monitoring-review :event :start-review-monitoring}
             (:transition monitoring-review)))
      (is (schema/succeeded? ready-to-merge))
      (is (= {:state :ready-to-merge :event :mark-ready-to-merge}
             (:transition ready-to-merge)))
      (is (schema/succeeded? merged))
      (is (= {:state :merged :event :merge} (:transition merged))))))

(deftest valid-transition-fix-loop-test
  (testing "fix loop transitions return to CI monitoring"
    (let [start-fixing (transition-result :monitoring-ci :fixing)
          restart-ci (transition-result :fixing :monitoring-ci)
          restart-fixing (transition-result :monitoring-review :fixing)]
      (is (schema/succeeded? start-fixing))
      (is (= {:state :fixing :event :start-fixing} (:transition start-fixing)))
      (is (schema/succeeded? restart-ci))
      (is (= {:state :monitoring-ci :event :start-ci-monitoring} (:transition restart-ci)))
      (is (schema/succeeded? restart-fixing))
      (is (= {:state :fixing :event :start-fixing} (:transition restart-fixing))))))

(deftest invalid-status-and-transition-test
  (testing "invalid statuses and undefined transitions are rejected"
    (let [invalid-state-result (transition-result :bogus-status :creating-pr)
          invalid-target-result (transition-result :pending :bogus-status)
          invalid-transition-result (transition-result :monitoring-ci :pending)]
      (is (schema/failed? invalid-state-result))
      (is (= :invalid-state (sut/transition-error-code invalid-state-result)))
      (is (schema/failed? invalid-target-result))
      (is (= :invalid-target-status (sut/transition-error-code invalid-target-result)))
      (is (schema/failed? invalid-transition-result))
      (is (= :invalid-transition (sut/transition-error-code invalid-transition-result)))
      (is (= (messages/t :fsm/invalid-state {:status :bogus-status})
             (sut/transition-error-message invalid-state-result)))
      (is (= (messages/t :fsm/invalid-target-status {:status :bogus-status})
             (sut/transition-error-message invalid-target-result)))
      (is (= (messages/t :fsm/invalid-transition
                         {:from-status :monitoring-ci
                          :to-status :pending})
             (sut/transition-error-message invalid-transition-result))))))

(deftest terminal-state-rejected-test
  (testing "terminal statuses reject further transitions"
    (is (= :terminal-state
           (sut/transition-error-code (transition-result :merged :monitoring-ci))))
    (is (= :terminal-state
           (sut/transition-error-code (transition-result :failed :creating-pr))))))

(deftest same-state-transition-remains-idempotent-test
  (testing "same-state transitions are allowed as idempotent updates"
    (let [monitoring-ci (transition-result :monitoring-ci :monitoring-ci)
          merged (transition-result :merged :merged)]
      (is (schema/succeeded? monitoring-ci))
      (is (= {:state :monitoring-ci :event nil} (:transition monitoring-ci)))
      (is (schema/succeeded? merged))
      (is (= {:state :merged :event nil} (:transition merged))))))

(deftest valid-transition-predicate-test
  (testing "valid-transition? requires recognized statuses"
    (is (true? (sut/valid-transition? :monitoring-ci :monitoring-ci)))
    (is (true? (sut/valid-transition? :monitoring-ci :monitoring-review)))
    (is (false? (sut/valid-transition? :bogus-status :bogus-status)))
    (is (false? (sut/valid-transition? :bogus-status :creating-pr)))))

(deftest valid-targets-test
  (testing "valid targets reflect the configured transition graph"
    (is (= #{:pending :creating-pr :monitoring-ci :failed}
           (sut/valid-targets :pending)))
    (is (= #{:ready-to-merge :monitoring-ci :merged :failed}
           (sut/valid-targets :ready-to-merge)))
    (is (= #{}
           (sut/valid-targets :bogus-status)))))
