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

(ns ai.miniforge.workflow.state-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.state :as state]))

(def sample-workflow
  {:workflow/id :test-workflow
   :workflow/version "1.0.0"
   :workflow/type :test
   :workflow/entry-phase :plan
   :workflow/exit-phases [:done]
   :workflow/phases [{:phase/id :plan}
                     {:phase/id :implement}
                     {:phase/id :done}]})

(deftest create-execution-state-test
  (testing "Create initial execution state"
    (let [input {:task "Test task"}
          exec-state (state/create-execution-state sample-workflow input)]
      (is (uuid? (:execution/id exec-state))
          "Should have a UUID execution ID")
      (is (= :test-workflow (:execution/workflow-id exec-state))
          "Should reference workflow ID")
      (is (= "1.0.0" (:execution/workflow-version exec-state))
          "Should reference workflow version")
      (is (= :pending (:execution/status exec-state))
          "Should start with pending status")
      (is (= :plan (:execution/current-phase exec-state))
          "Should start at entry phase")
      (is (= {} (:execution/phase-results exec-state))
          "Should start with empty phase results")
      (is (= [] (:execution/artifacts exec-state))
          "Should start with no artifacts")
      (is (= [] (:execution/errors exec-state))
          "Should start with no errors")
      (is (= {:tokens 0 :cost-usd 0.0 :duration-ms 0}
             (:execution/metrics exec-state))
          "Should start with zero metrics")
      (is (= [] (:execution/history exec-state))
          "Should start with empty history")
      (is (= input (:execution/input exec-state))
          "Should store input data")
      (is (number? (:execution/created-at exec-state))
          "Should have creation timestamp")
      (is (number? (:execution/updated-at exec-state))
          "Should have update timestamp"))))

(deftest transition-to-phase-test
  (testing "Transition to new phase"
    (let [exec-state (state/create-execution-state sample-workflow {})
          exec-state2 (state/transition-to-phase exec-state :implement)]
      (is (= :implement (:execution/current-phase exec-state2))
          "Should update current phase")
      (is (= :running (:execution/status exec-state2))
          "Should update status to running")
      ;; History now includes both FSM transition (pending->running) and phase transition
      (is (= 2 (count (:execution/history exec-state2)))
          "Should have FSM and phase transitions in history")
      ;; Phase transition is the second entry (after FSM transition)
      (let [phase-transition (second (:execution/history exec-state2))]
        (is (= :plan (:from-phase phase-transition))
            "Transition should record from phase")
        (is (= :implement (:to-phase phase-transition))
            "Transition should record to phase")
        (is (= :advance (:reason phase-transition))
            "Transition should record reason")
        (is (number? (:timestamp phase-transition))
            "Transition should have timestamp"))))

  (testing "Transition with custom reason"
    (let [exec-state (state/create-execution-state sample-workflow {})
          exec-state2 (state/transition-to-phase exec-state :implement :rollback)]
      (is (= :implement (:execution/current-phase exec-state2))
          "Should update current phase")
      ;; Phase transition is the second entry
      (let [phase-transition (second (:execution/history exec-state2))]
        (is (= :rollback (:reason phase-transition))
            "Should record custom reason")))))

(deftest record-phase-result-test
  (testing "Record successful phase result"
    (let [exec-state (state/create-execution-state sample-workflow {})
          exec-state2 (state/transition-to-phase exec-state :plan)
          result {:success? true
                  :artifacts [{:type :plan :content "Plan content"}]
                  :errors []
                  :metrics {:tokens 100 :cost-usd 0.01 :duration-ms 5000}}
          exec-state3 (state/record-phase-result exec-state2 :plan result)]
      (is (= result (get-in exec-state3 [:execution/phase-results :plan]))
          "Should store phase result")
      (is (= 1 (count (:execution/artifacts exec-state3)))
          "Should add artifacts")
      (is (= [] (:execution/errors exec-state3))
          "Should have no errors")
      (is (= {:tokens 100 :cost-usd 0.01 :duration-ms 5000}
             (:execution/metrics exec-state3))
          "Should accumulate metrics")))

  (testing "Record failed phase result"
    (let [exec-state (state/create-execution-state sample-workflow {})
          exec-state2 (state/transition-to-phase exec-state :plan)
          result {:success? false
                  :artifacts []
                  :errors [{:type :plan-failed :message "Planning failed"}]
                  :metrics {:tokens 50 :cost-usd 0.005 :duration-ms 2000}}
          exec-state3 (state/record-phase-result exec-state2 :plan result)]
      (is (= 1 (count (:execution/errors exec-state3)))
          "Should add errors")
      (is (= [] (:execution/artifacts exec-state3))
          "Should have no artifacts")
      (is (= {:tokens 50 :cost-usd 0.005 :duration-ms 2000}
             (:execution/metrics exec-state3))
          "Should accumulate metrics even on failure")))

  (testing "Accumulate metrics across multiple phases"
    (let [exec-state (state/create-execution-state sample-workflow {})
          result1 {:success? true
                   :artifacts []
                   :errors []
                   :metrics {:tokens 100 :cost-usd 0.01 :duration-ms 5000}}
          result2 {:success? true
                   :artifacts []
                   :errors []
                   :metrics {:tokens 200 :cost-usd 0.02 :duration-ms 10000}}
          exec-state2 (-> exec-state
                          (state/transition-to-phase :plan)
                          (state/record-phase-result :plan result1)
                          (state/transition-to-phase :implement)
                          (state/record-phase-result :implement result2))]
      (is (= {:tokens 300 :cost-usd 0.03 :duration-ms 15000}
             (:execution/metrics exec-state2))
          "Should accumulate metrics across phases"))))

(deftest mark-completed-test
  (testing "Mark execution as completed"
    ;; Must first transition to running before completing
    (let [exec-state (state/create-execution-state sample-workflow {})
          exec-state-running (state/transition-status exec-state :start)
          exec-state2 (state/mark-completed exec-state-running)]
      (is (= :completed (:execution/status exec-state2))
          "Should set status to completed")
      (is (number? (:execution/completed-at exec-state2))
          "Should add completion timestamp")
      (is (state/completed? exec-state2)
          "completed? predicate should return true")
      (is (not (state/failed? exec-state2))
          "failed? predicate should return false")
      (is (not (state/running? exec-state2))
          "running? predicate should return false"))))

(deftest mark-failed-test
  (testing "Mark execution as failed with error map"
    ;; Must first transition to running before failing
    (let [exec-state (state/create-execution-state sample-workflow {})
          exec-state-running (state/transition-status exec-state :start)
          error {:type :execution-failed :message "Something went wrong"}
          exec-state2 (state/mark-failed exec-state-running error)]
      (is (= :failed (:execution/status exec-state2))
          "Should set status to failed")
      (is (= 1 (count (:execution/errors exec-state2)))
          "Should add error to errors list")
      (is (= error (first (:execution/errors exec-state2)))
          "Should store error map")
      (is (number? (:execution/failed-at exec-state2))
          "Should add failure timestamp")
      (is (state/failed? exec-state2)
          "failed? predicate should return true")
      (is (not (state/completed? exec-state2))
          "completed? predicate should return false")))

  (testing "Mark execution as failed with error string"
    ;; Must first transition to running before failing
    (let [exec-state (state/create-execution-state sample-workflow {})
          exec-state-running (state/transition-status exec-state :start)
          exec-state2 (state/mark-failed exec-state-running "Error message")]
      (is (= :failed (:execution/status exec-state2))
          "Should set status to failed")
      (is (= 1 (count (:execution/errors exec-state2)))
          "Should add error to errors list")
      (is (= :execution-failed (:type (first (:execution/errors exec-state2))))
          "Should convert string to error map with type")
      (is (= "Error message" (:message (first (:execution/errors exec-state2))))
          "Should convert string to error map with message"))))

(deftest state-query-functions-test
  (testing "has-phase-result? and get-phase-result"
    (let [exec-state (state/create-execution-state sample-workflow {})
          result {:success? true :artifacts [] :errors [] :metrics {}}
          exec-state2 (state/record-phase-result exec-state :plan result)]
      (is (not (state/has-phase-result? exec-state :plan))
          "Should return false before phase is executed")
      (is (state/has-phase-result? exec-state2 :plan)
          "Should return true after phase is executed")
      (is (= result (state/get-phase-result exec-state2 :plan))
          "Should return phase result")
      (is (nil? (state/get-phase-result exec-state2 :implement))
          "Should return nil for non-executed phase")))

  (testing "get-current-metrics"
    (let [exec-state (state/create-execution-state sample-workflow {})
          result {:success? true
                  :artifacts []
                  :errors []
                  :metrics {:tokens 100 :cost-usd 0.01 :duration-ms 5000}}
          exec-state2 (state/record-phase-result exec-state :plan result)]
      (is (= {:tokens 0 :cost-usd 0.0 :duration-ms 0}
             (state/get-current-metrics exec-state))
          "Should return zero metrics initially")
      (is (= {:tokens 100 :cost-usd 0.01 :duration-ms 5000}
             (state/get-current-metrics exec-state2))
          "Should return accumulated metrics")))

  (testing "get-duration-ms"
    ;; Must first transition to running before completing
    (let [exec-state (state/create-execution-state sample-workflow {})
          exec-state-running (state/transition-status exec-state :start)]
      (Thread/sleep 10)  ; Wait a bit
      (let [exec-state2 (state/mark-completed exec-state-running)
            duration (state/get-duration-ms exec-state2)]
        (is (pos? duration)
            "Duration should be positive")
        (is (>= duration 10)
            "Duration should be at least 10ms")))))

(deftest state-transitions-flow-test
  (testing "Complete workflow execution flow"
    (let [input {:task "Build feature"}
          exec-state (state/create-execution-state sample-workflow input)
          ;; Execute plan phase (this will auto-start the workflow)
          exec-state2 (-> exec-state
                          (state/transition-to-phase :plan)
                          (state/record-phase-result :plan
                                                     {:success? true
                                                      :artifacts [{:type :plan}]
                                                      :errors []
                                                      :metrics {:tokens 100}}))
          ;; Execute implement phase
          exec-state3 (-> exec-state2
                          (state/transition-to-phase :implement)
                          (state/record-phase-result :implement
                                                     {:success? true
                                                      :artifacts [{:type :code}]
                                                      :errors []
                                                      :metrics {:tokens 200}}))
          ;; Complete workflow
          exec-state4 (-> exec-state3
                          (state/transition-to-phase :done)
                          (state/mark-completed))]

      (is (= :done (:execution/current-phase exec-state4))
          "Should end at done phase")
      (is (state/completed? exec-state4)
          "Should be completed")
      (is (= 2 (count (:execution/phase-results exec-state4)))
          "Should have results for both phases")
      (is (= 2 (count (:execution/artifacts exec-state4)))
          "Should have artifacts from both phases")
      (is (= {:tokens 300 :cost-usd 0.0 :duration-ms 0}
             (:execution/metrics exec-state4))
          "Should have accumulated metrics")
      ;; History now includes: FSM (pending->running), phase (plan->plan),
      ;; phase (plan->implement), phase (implement->done), FSM (running->completed)
      (is (= 5 (count (:execution/history exec-state4)))
          "Should have FSM and phase transitions in history"))))
