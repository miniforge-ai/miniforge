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

(ns ai.miniforge.workflow.fsm-test
  "Tests for workflow FSM."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.fsm :as fsm]))

(deftest workflow-states-test
  (testing "All workflow states are defined"
    (is (contains? fsm/workflow-states :pending))
    (is (contains? fsm/workflow-states :running))
    (is (contains? fsm/workflow-states :completed))
    (is (contains? fsm/workflow-states :failed))
    (is (contains? fsm/workflow-states :paused))
    (is (contains? fsm/workflow-states :cancelled))))

(deftest valid-transition-test
  (testing "Valid transitions from pending"
    (is (fsm/valid-transition? :pending :start)))

  (testing "Valid transitions from running"
    (is (fsm/valid-transition? :running :complete))
    (is (fsm/valid-transition? :running :fail))
    (is (fsm/valid-transition? :running :pause))
    (is (fsm/valid-transition? :running :cancel)))

  (testing "Valid transitions from paused"
    (is (fsm/valid-transition? :paused :resume))
    (is (fsm/valid-transition? :paused :cancel)))

  (testing "Invalid transitions"
    (is (not (fsm/valid-transition? :pending :complete)))
    (is (not (fsm/valid-transition? :pending :fail)))
    (is (not (fsm/valid-transition? :completed :start)))
    (is (not (fsm/valid-transition? :failed :resume)))))

(deftest terminal-state-test
  (testing "Terminal states"
    (is (fsm/terminal-state? :completed))
    (is (fsm/terminal-state? :failed))
    (is (fsm/terminal-state? :cancelled)))

  (testing "Non-terminal states"
    (is (not (fsm/terminal-state? :pending)))
    (is (not (fsm/terminal-state? :running)))
    (is (not (fsm/terminal-state? :paused)))))

(deftest next-state-test
  (testing "Next state for valid transitions"
    (is (= :running (fsm/next-state :pending :start)))
    (is (= :completed (fsm/next-state :running :complete)))
    (is (= :failed (fsm/next-state :running :fail)))
    (is (= :paused (fsm/next-state :running :pause)))
    (is (= :cancelled (fsm/next-state :running :cancel)))
    (is (= :running (fsm/next-state :paused :resume)))
    (is (= :cancelled (fsm/next-state :paused :cancel))))

  (testing "Next state for invalid transitions"
    (is (nil? (fsm/next-state :pending :complete)))
    (is (nil? (fsm/next-state :completed :start)))))

(deftest transition-test
  (testing "Successful transitions"
    (let [result (fsm/transition :pending :start)]
      (is (:success? result))
      (is (= :running (:state result)))))

  (testing "Failed transition - terminal state"
    (let [result (fsm/transition :completed :start)]
      (is (not (:success? result)))
      (is (= :terminal-state (:error result)))))

  (testing "Failed transition - invalid state"
    (let [result (fsm/transition :invalid-state :start)]
      (is (not (:success? result)))
      (is (= :invalid-state (:error result)))))

  (testing "Failed transition - invalid transition"
    (let [result (fsm/transition :pending :complete)]
      (is (not (:success? result)))
      (is (= :invalid-transition (:error result)))))

  (testing "Transition with guard function"
    (let [guard (fn [_state _event] false)
          result (fsm/transition :pending :start guard)]
      (is (not (:success? result)))
      (is (= :guard-failed (:error result))))))

(deftest valid-state-test
  (testing "Valid states"
    (is (fsm/valid-state? :pending))
    (is (fsm/valid-state? :running))
    (is (fsm/valid-state? :completed)))

  (testing "Invalid states"
    (is (not (fsm/valid-state? :invalid)))
    (is (not (fsm/valid-state? :unknown)))))

(deftest get-available-events-test
  (testing "Available events from pending"
    (let [events (fsm/get-available-events :pending)]
      (is (= [:start] events))))

  (testing "Available events from running"
    (let [events (set (fsm/get-available-events :running))]
      (is (= #{:complete :fail :pause :cancel} events))))

  (testing "Available events from paused"
    (let [events (set (fsm/get-available-events :paused))]
      (is (= #{:resume :cancel} events))))

  (testing "No available events from terminal states"
    (is (empty? (fsm/get-available-events :completed)))
    (is (empty? (fsm/get-available-events :failed)))
    (is (empty? (fsm/get-available-events :cancelled)))))

(deftest fsm-graph-test
  (testing "FSM graph structure"
    (let [graph (fsm/fsm-graph)]
      (is (set? (:states graph)))
      (is (vector? (:transitions graph)))
      (is (set? (:terminal-states graph)))
      (is (= fsm/workflow-states (:states graph)))
      (is (= fsm/terminal-states (:terminal-states graph))))))

(deftest fsm-workflow-lifecycle-test
  (testing "Complete workflow lifecycle"
    ;; Start: pending -> running
    (let [r1 (fsm/transition :pending :start)]
      (is (:success? r1))
      (is (= :running (:state r1)))

      ;; Complete: running -> completed
      (let [r2 (fsm/transition (:state r1) :complete)]
        (is (:success? r2))
        (is (= :completed (:state r2)))

        ;; Cannot transition from terminal state
        (let [r3 (fsm/transition (:state r2) :start)]
          (is (not (:success? r3)))
          (is (= :terminal-state (:error r3)))))))

  (testing "Failed workflow lifecycle"
    ;; Start: pending -> running
    (let [r1 (fsm/transition :pending :start)]
      (is (:success? r1))
      (is (= :running (:state r1)))

      ;; Fail: running -> failed
      (let [r2 (fsm/transition (:state r1) :fail)]
        (is (:success? r2))
        (is (= :failed (:state r2))))))

  (testing "Paused workflow lifecycle"
    ;; Start: pending -> running
    (let [r1 (fsm/transition :pending :start)]
      (is (:success? r1))
      (is (= :running (:state r1)))

      ;; Pause: running -> paused
      (let [r2 (fsm/transition (:state r1) :pause)]
        (is (:success? r2))
        (is (= :paused (:state r2)))

        ;; Resume: paused -> running
        (let [r3 (fsm/transition (:state r2) :resume)]
          (is (:success? r3))
          (is (= :running (:state r3)))

          ;; Complete: running -> completed
          (let [r4 (fsm/transition (:state r3) :complete)]
            (is (:success? r4))
            (is (= :completed (:state r4)))))))))

(deftest compiled-execution-machine-projects-phase-state-test
  (let [workflow {:workflow/id :test
                  :workflow/pipeline [{:phase :plan}
                                      {:phase :implement}
                                      {:phase :done}]}
        machine (fsm/compile-execution-machine workflow)
        pending-state (fsm/initialize-execution machine)
        running-state (fsm/start-execution machine pending-state)]
    (testing "pending state projects no active phase"
      (is (= {:execution/status :pending
              :execution/current-phase nil
              :execution/phase-index nil
              :execution/redirect-count 0}
             (fsm/execution-projection machine pending-state))))
    (testing "start enters the first pipeline phase"
      (is (= :running (fsm/execution-status machine running-state)))
      (is (= :plan (fsm/current-phase-id machine running-state)))
      (is (= 0 (fsm/current-phase-index machine running-state))))))

(deftest compiled-execution-machine-follows-phase-transitions-test
  (let [workflow {:workflow/id :test
                  :workflow/pipeline [{:phase :plan}
                                      {:phase :implement}
                                      {:phase :verify :on-fail :implement}
                                      {:phase :done}]}
        machine (fsm/compile-execution-machine workflow)
        s0 (fsm/start-execution machine (fsm/initialize-execution machine))
        s1 (fsm/transition-execution machine s0 :phase/succeed)
        s2 (fsm/transition-execution machine s1 :phase/succeed)
        s3 (fsm/transition-execution machine s2 (fsm/redirect-event :implement))
        s4 (fsm/transition-execution machine s3 :pause)
        s5 (fsm/transition-execution machine s4 :resume)
        s6 (fsm/transition-execution machine s5 :phase/succeed)
        s7 (fsm/transition-execution machine s6 :phase/already-done)]
    (testing "success transitions advance through the compiled phase graph"
      (is (= :implement (fsm/current-phase-id machine s1)))
      (is (= :verify (fsm/current-phase-id machine s2))))
    (testing "redirect transitions return to the configured phase and increment redirect count"
      (is (= :implement (fsm/current-phase-id machine s3)))
      (is (= 1 (:execution/redirect-count (fsm/execution-projection machine s3)))))
    (testing "pause and resume are phase-local machine states"
      (is (= :paused (fsm/execution-status machine s4)))
      (is (= :implement (fsm/current-phase-id machine s4)))
      (is (= :running (fsm/execution-status machine s5))))
    (testing "already-done transitions jump to :done and then completion"
      (is (= :verify (fsm/current-phase-id machine s6)))
      (is (= :done (fsm/current-phase-id machine s7))))))

(deftest validate-execution-machine-test
  (testing "detects unresolved transition targets"
    (let [result (fsm/validate-execution-machine
                  {:workflow/id :bad
                   :workflow/pipeline [{:phase :plan :on-success :missing}]})]
      (is (false? (:valid? result)))
      (is (= :unknown-on-success-target (-> result :errors first :error)))))
  (testing "warns on duplicate phase ids because target resolution becomes ambiguous"
    (let [result (fsm/validate-execution-machine
                  {:workflow/id :dup
                   :workflow/pipeline [{:phase :plan}
                                       {:phase :plan}]})]
      (is (true? (:valid? result)))
      (is (= :duplicate-phase-identifiers (-> result :warnings first :warning))))))
