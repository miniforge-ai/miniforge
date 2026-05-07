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

(ns ai.miniforge.task.anomaly.transition-result-test
  "Coverage for `core/transition-result` (anomaly-returning) and the
   throwing boundary helper `core/validate-transition` reached via
   `transition-task!` / `start-task!` / `complete-task!` /
   `fail-task!` / `block-task!` / `unblock-task!`.

   FSM rejection returns an `:invalid-input` anomaly. The boundary
   helper escalates the anomaly to a slingshot
   `:anomalies/conflict` throw — used by mutating callers that treat
   FSM rejection as a state-mismatch programmer error rather than a
   runtime anomaly to be carried as data."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.task.core :as core])
  (:import
   (clojure.lang ExceptionInfo)))

(defn- reset-store-fixture [f]
  (core/reset-store!)
  (f)
  (core/reset-store!))

(use-fixtures :each reset-store-fixture)

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)

(deftest transition-result-returns-to-state-on-valid-transition
  (testing ":pending -> :running is allowed by the task FSM"
    (let [result (core/transition-result :pending :running)]
      (is (not (anomaly/anomaly? result)))
      (is (= :running result))))
  (testing ":running -> :completed is allowed"
    (is (= :completed (core/transition-result :running :completed))))
  (testing ":pending -> :blocked is allowed"
    (is (= :blocked (core/transition-result :pending :blocked))))
  (testing ":blocked -> :pending is allowed"
    (is (= :pending (core/transition-result :blocked :pending)))))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)

(deftest transition-result-returns-anomaly-on-invalid-transition
  (testing "rejected transition yields :invalid-input anomaly with FSM diagnostics"
    (let [result (core/transition-result :pending :completed)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (let [data (:anomaly/data result)]
        (is (= :pending (:from data)))
        (is (= :completed (:to data)))
        (is (set? (:valid-targets data)))
        (is (contains? (:valid-targets data) :running))
        (is (contains? (:valid-targets data) :blocked))
        (is (not (contains? (:valid-targets data) :completed)))))))

(deftest transition-result-rejection-from-terminal-state
  (testing "terminal :completed has no outgoing transitions; any target rejected"
    (let [result (core/transition-result :completed :running)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= #{} (:valid-targets (:anomaly/data result)))))))

(deftest transition-result-rejection-returns-anomaly-not-state
  (testing "rejected transition returns the anomaly itself, not the to-state"
    (let [result (core/transition-result :running :pending)]
      (is (anomaly/anomaly? result))
      ;; A buggy implementation that returned to-state regardless would
      ;; report :pending here.
      (is (not= :pending result)))))

;------------------------------------------------------------------------------ Boundary helper escalates via slingshot

(deftest validate-transition-throws-on-fsm-rejection
  (testing "validate-transition escalates an FSM rejection to slingshot"
    (is (thrown? ExceptionInfo (core/validate-transition :pending :completed)))))

(deftest validate-transition-thrown-ex-data-preserves-anomaly-shape
  (testing "ex-data carries :anomalies/conflict for try+ catches"
    (try
      (core/validate-transition :running :pending)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :anomalies/conflict (:anomaly/category data)))
          (is (= :running (:from data)))
          (is (= :pending (:to data)))
          (is (set? (:valid-targets data))))))))

(deftest validate-transition-returns-to-state-on-success
  (testing "happy path returns to-state directly"
    (is (= :running (core/validate-transition :pending :running)))
    (is (= :failed (core/validate-transition :running :failed)))))

;------------------------------------------------------------------------------ Public mutating callers escalate via slingshot

(deftest start-task-throws-when-status-not-pending
  (testing "start-task! on a :running task escalates to slingshot"
    (let [t (core/create-task! {:task/type :implement})]
      (core/start-task! (:task/id t) (random-uuid))
      (is (thrown? ExceptionInfo
                   (core/start-task! (:task/id t) (random-uuid)))))))

(deftest complete-task-throws-when-status-not-running
  (testing "complete-task! on a :pending task escalates to slingshot"
    (let [t (core/create-task! {:task/type :implement})]
      (is (thrown? ExceptionInfo
                   (core/complete-task! (:task/id t) {}))))))
