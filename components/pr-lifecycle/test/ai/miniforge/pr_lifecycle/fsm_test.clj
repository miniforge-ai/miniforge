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
   [ai.miniforge.pr-lifecycle.fsm :as sut]))

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
    (is (= {:success? true :state :creating-pr :event :create-pr}
           (transition-result :pending :creating-pr)))
    (is (= {:success? true :state :monitoring-ci :event :start-ci-monitoring}
           (transition-result :creating-pr :monitoring-ci)))
    (is (= {:success? true :state :monitoring-review :event :start-review-monitoring}
           (transition-result :monitoring-ci :monitoring-review)))
    (is (= {:success? true :state :ready-to-merge :event :mark-ready-to-merge}
           (transition-result :monitoring-review :ready-to-merge)))
    (is (= {:success? true :state :merged :event :merge}
           (transition-result :ready-to-merge :merged)))))

(deftest valid-transition-fix-loop-test
  (testing "fix loop transitions return to CI monitoring"
    (is (= {:success? true :state :fixing :event :start-fixing}
           (transition-result :monitoring-ci :fixing)))
    (is (= {:success? true :state :monitoring-ci :event :start-ci-monitoring}
           (transition-result :fixing :monitoring-ci)))
    (is (= {:success? true :state :fixing :event :start-fixing}
           (transition-result :monitoring-review :fixing)))))

(deftest invalid-status-and-transition-test
  (testing "invalid statuses and undefined transitions are rejected"
    (is (= :invalid-state (:error (transition-result :bogus-status :creating-pr))))
    (is (= :invalid-target-status (:error (transition-result :pending :bogus-status))))
    (is (= :invalid-transition (:error (transition-result :monitoring-ci :pending))))))

(deftest terminal-state-rejected-test
  (testing "terminal statuses reject further transitions"
    (is (= :terminal-state (:error (transition-result :merged :monitoring-ci))))
    (is (= :terminal-state (:error (transition-result :failed :creating-pr))))))

(deftest same-state-transition-remains-idempotent-test
  (testing "same-state transitions are allowed as idempotent updates"
    (is (= {:success? true :state :monitoring-ci :event nil}
           (transition-result :monitoring-ci :monitoring-ci)))
    (is (= {:success? true :state :merged :event nil}
           (transition-result :merged :merged)))))

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
