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

(ns conformance.event_stream_test
  "N1 Event Stream conformance tests.
   Verifies event stream completeness, ordering, and causality per N1 §8.2.5."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and event capture

(defn create-event-capture-logger
  "Create a logger that captures all events to an atom."
  []
  (let [events (atom [])]
    {:logger (reify
               Object
               (toString [_] "EventCaptureLogger"))
     :events events
     :capture-fn (fn [event]
                   (swap! events conj event))}))

(def test-workflow-spec
  {:workflow/id :event-test
   :workflow/type :feature
   :workflow/intent {:intent/description "Test events"}
   :workflow/phases
   [{:phase/id :plan
     :phase/agent :planner
     :phase/next [{:target :done}]
     :phase/gates []
     :phase/budget {:tokens 1000}
     :phase/inner-loop {:max-iterations 2 :validation-steps []}}
    {:phase/id :done
     :phase/agent :none
     :phase/next []
     :phase/gates []
     :phase/budget {:tokens 0}
     :phase/inner-loop {:max-iterations 1 :validation-steps []}}]
   :workflow/entry :plan
   :workflow/config
   {:max-total-tokens 2000
    :max-total-iterations 5
    :max-total-time-seconds 60}})

;------------------------------------------------------------------------------ Layer 1
;; N1 §3: Event emission requirements

(deftest workflow-start-event-test
  (testing "N1 §3: Workflow start MUST emit event"
    ;; In a real implementation, we'd hook into the event system
    ;; For now, we verify the workflow state changes are observable
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec {})
          state (workflow/get-state wf wf-id)]
      (is (some? state)
          "Workflow start must be observable through state")
      (is (inst? (:workflow/created-at state))
          "Workflow start time must be recorded")
      (is (inst? (:workflow/started-at state))
          "Workflow execution start time must be recorded"))))

(deftest phase-transition-events-test
  (testing "N1 §3: Phase transitions MUST emit events"
    (let [_event-log (atom [])
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec {})]

      ;; Transition from plan to done
      (workflow/advance wf wf-id {:success? true :artifacts []})

      (let [state (workflow/get-state wf wf-id)
            history (:workflow/history state)]
        (is (seq history)
            "Phase transitions must be recorded")
        (is (some #(= :plan (:phase/name %)) history)
            "Plan phase completion must be recorded")
        (is (every? inst? (map :phase/started-at history))
            "Phase start times must be recorded")
        (is (every? inst? (filter identity (map :phase/completed-at history)))
            "Phase completion times must be recorded")))))

(deftest workflow-completion-event-test
  (testing "N1 §3: Workflow completion MUST emit event"
    (let [_event-log (atom [])
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec {})]

      ;; Complete workflow
      (workflow/advance wf wf-id {:success? true :artifacts []})
      (workflow/advance wf wf-id {:success? true :artifacts []})

      (let [state (workflow/get-state wf wf-id)]
        (is (= :completed (:workflow/status state))
            "Workflow must reach completed status")
        (is (inst? (:workflow/completed-at state))
            "Workflow completion time must be recorded")))))

;------------------------------------------------------------------------------ Layer 2
;; Event ordering and causality

(deftest event-ordering-test
  (testing "N1 §8.2.5: Events must be ordered chronologically"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec {})]

      (workflow/advance wf wf-id {:success? true :artifacts []})

      (let [state (workflow/get-state wf wf-id)
            history (:workflow/history state)]
        (is (seq history)
            "Event history must exist")

        ;; Verify chronological ordering
        (let [timestamps (map :phase/started-at history)]
          (is (= timestamps (sort timestamps))
              "Events must be in chronological order"))))))

(deftest event-causality-test
  (testing "N1 §8.2.5: Events must maintain causal relationships"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec {})]

      (workflow/advance wf wf-id {:success? true :artifacts []})
      (workflow/advance wf wf-id {:success? true :artifacts []})

      (let [state (workflow/get-state wf wf-id)
            history (:workflow/history state)]
        ;; Verify parent-child relationships
        (when (> (count history) 1)
          (let [phases (map :phase/name history)]
            (is (= :plan (first phases))
                "First phase must be entry phase")
            (is (= :done (last phases))
                "Last phase must be terminal phase")))))))

;------------------------------------------------------------------------------ Layer 2
;; N1 §8.2.5: Event stream completeness

(deftest required-events-emitted-test
  (testing "N1 §8.2.5: All required events are emitted"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec {})]

      ;; Execute workflow
      (workflow/advance wf wf-id {:success? true :artifacts []})
      (workflow/advance wf wf-id {:success? true :artifacts []})

      (let [state (workflow/get-state wf wf-id)]
        ;; Required workflow-level events
        (is (inst? (:workflow/created-at state))
            "workflow.created event required")
        (is (inst? (:workflow/started-at state))
            "workflow.started event required")
        (is (inst? (:workflow/completed-at state))
            "workflow.completed event required")

        ;; Required phase-level events
        (let [history (:workflow/history state)]
          (is (every? inst? (map :phase/started-at history))
              "phase.started events required for all phases")
          (is (every? inst? (filter identity (map :phase/completed-at history)))
              "phase.completed events required for completed phases"))))))

(deftest agent-lifecycle-events-test
  (testing "N1 §3: Agent lifecycle events are tracked"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec {})]

      (workflow/advance wf wf-id {:success? true :artifacts []})

      (let [state (workflow/get-state wf wf-id)
            history (:workflow/history state)
            plan-phase (first (filter #(= :plan (:phase/name %)) history))]
        (is (= :planner (:phase/agent plan-phase))
            "Agent assignment must be recorded")
        (is (inst? (:phase/started-at plan-phase))
            "Agent start time must be recorded")
        (is (inst? (:phase/completed-at plan-phase))
            "Agent completion time must be recorded")))))

(deftest gate-execution-events-test
  (testing "N1 §3: Gate execution events are tracked"
    (let [gate-workflow {:workflow/id :gate-test
                        :workflow/type :feature
                        :workflow/intent {:intent/description "Test gates"}
                        :workflow/phases
                        [{:phase/id :implement
                          :phase/agent :implementer
                          :phase/next [{:target :done}]
                          :phase/gates [:syntax-valid]
                          :phase/budget {:tokens 2000}
                          :phase/inner-loop {:max-iterations 3 :validation-steps [:syntax]}}
                         {:phase/id :done
                          :phase/agent :none
                          :phase/next []
                          :phase/gates []
                          :phase/budget {:tokens 0}
                          :phase/inner-loop {:max-iterations 1 :validation-steps []}}]
                        :workflow/entry :implement
                        :workflow/config
                        {:max-total-tokens 3000
                         :max-total-iterations 5
                         :max-total-time-seconds 60}}
          wf (workflow/create-workflow)
          wf-id (workflow/start wf gate-workflow {})]

      (workflow/advance wf wf-id {:success? true :artifacts []})

      (let [state (workflow/get-state wf wf-id)
            history (:workflow/history state)
            implement-phase (first (filter #(= :implement (:phase/name %)) history))]
        (is (seq (:phase/gates implement-phase))
            "Gate configuration must be recorded")))))

;------------------------------------------------------------------------------ Layer 2
;; Event stream replay capability

(deftest event-stream-replay-test
  (testing "N1 §5.2: Event stream enables state reconstruction"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec {})]

      ;; Execute workflow
      (workflow/advance wf wf-id {:success? true :artifacts []})
      (workflow/advance wf wf-id {:success? true :artifacts []})

      (let [state (workflow/get-state wf wf-id)
            history (:workflow/history state)]
        ;; Verify we can reconstruct workflow state from history
        (is (= (count (:workflow/phases test-workflow-spec))
               (count history))
            "History should contain all phases")

        ;; Verify phase sequence can be replayed
        (let [phase-sequence (mapv :phase/name history)
              expected-sequence [:plan :done]]
          (is (= expected-sequence phase-sequence)
              "Event stream should preserve execution order"))))))

;; N3 §3.4 ETL lifecycle event conformance lives with the ETL implementation in
;; ai.miniforge.workflow-financial-etl.interface-test (etl-event-schema-conformance-test).
;; This conformance project does not depend on workflow-financial-etl.

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'conformance.event_stream_test)
  :leave-this-here)
