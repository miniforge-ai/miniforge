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

(ns ai.miniforge.workflow.anomaly.transition-status-test
  "Coverage for `state/transition-status` (anomaly-returning) and the
   private boundary helper `state/transition-status!` reached via
   `mark-completed` / `mark-failed` / `transition-to-phase`.

   The FSM rejection path returns an `:invalid-input` anomaly. The
   in-component `*!` boundary helper escalates the anomaly to a
   slingshot `:anomalies.workflow/invalid-transition` throw — only
   used at workflow-definition-invariant boundaries where the FSM
   rejecting is a programmer error, not a runtime condition."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.state :as state])
  (:import (clojure.lang ExceptionInfo)))

(def sample-workflow
  {:workflow/id :test
   :workflow/version "1.0.0"
   :workflow/phases [{:phase/id :start}]})

(defn- pending-state []
  (state/create-execution-state sample-workflow {:input "x"}))

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)

(deftest transition-status-returns-state-on-valid-event
  (testing "valid :start event from :pending advances to :running"
    (let [s      (pending-state)
          result (state/transition-status s :start)]
      (is (not (anomaly/anomaly? result)))
      (is (= :running (:execution/status result))))))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)

(deftest transition-status-returns-anomaly-on-invalid-event
  (testing "invalid event yields :invalid-input anomaly with FSM diagnostics"
    (let [s      (pending-state)
          result (state/transition-status s :complete)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (let [data (:anomaly/data result)]
        (is (= :pending (:current-status data)))
        (is (= :complete (:event data)))
        (is (some? (:error data)))
        (is (some? (:fsm-message data)))))))

(deftest transition-status-rejection-returns-anomaly-not-state
  (testing "rejected transition returns the anomaly itself, not a partially-transitioned state map"
    (let [s      (pending-state)
          result (state/transition-status s :complete)
          data   (:anomaly/data result)]
      (is (anomaly/anomaly? result))
      ;; Anomaly carries the status the FSM was in *at evaluation time*.
      ;; A buggy implementation that advanced the status before flagging
      ;; the rejection would report the wrong :current-status here.
      (is (= :pending (:current-status data)))
      ;; Returned value is the anomaly itself, not a state map carrying
      ;; both an updated :execution/status and the anomaly.
      (is (not (contains? result :execution/status))))))

;------------------------------------------------------------------------------ Boundary helper escalates via slingshot
;;
;; `transition-status!` is private and reached via `mark-completed` /
;; `mark-failed` / `transition-to-phase`. These helpers represent
;; workflow-definition invariants — a rejection at one of them is a
;; programmer error in the workflow shape, not a runtime anomaly.
;; Escalating to a slingshot throw preserves the legacy ex-info shape
;; that runner / supervisor consumers depend on.

(deftest mark-completed-throws-on-fsm-rejection
  (testing "mark-completed escalates an FSM rejection to slingshot"
    (let [;; Build a state already in :completed — :complete event from
          ;; :completed should be rejected by the FSM.
          s (assoc (pending-state) :execution/status :completed)]
      (is (thrown? ExceptionInfo (state/mark-completed s))))))

(deftest mark-failed-throws-on-fsm-rejection
  (testing "mark-failed escalates an FSM rejection to slingshot"
    (let [s (assoc (pending-state) :execution/status :completed)]
      (is (thrown? ExceptionInfo (state/mark-failed s "boom"))))))

(deftest mark-completed-thrown-ex-data-preserves-slingshot-shape
  (testing "ex-data carries :anomalies.workflow/invalid-transition for try+ catches"
    (let [s (assoc (pending-state) :execution/status :completed)]
      (try
        (state/mark-completed s)
        (is false "should have thrown")
        (catch ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :anomalies.workflow/invalid-transition (:anomaly/category data)))
            (is (= :completed (:current-status data)))
            (is (= :complete (:event data)))
            (is (some? (:error data)))
            (is (some? (:message data)))))))))

;------------------------------------------------------------------------------ Boundary helper happy path

(deftest mark-completed-advances-pending-to-completed
  (testing "from :pending, mark-completed transitions :pending → :running → :completed"
    (let [result (state/mark-completed (pending-state))]
      (is (= :completed (:execution/status result)))
      (is (some? (:execution/completed-at result))))))

(deftest mark-failed-advances-pending-to-failed
  (testing "from :pending, mark-failed transitions :pending → :running → :failed"
    (let [result (state/mark-failed (pending-state) {:type :test :message "boom"})]
      (is (= :failed (:execution/status result)))
      (is (some? (:execution/failed-at result)))
      (is (= 1 (count (:execution/errors result)))))))
