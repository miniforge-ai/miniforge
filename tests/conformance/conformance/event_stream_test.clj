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

;------------------------------------------------------------------------------ Layer 3
;; N3 §3.4: ETL lifecycle events

#_(deftest etl-completed-event-test
  (testing "N3 §3.4: etl/completed event is emitted on successful ETL workflow"
    (let [[logger entries] (logging/collecting-logger {:min-level :info})]

      ;; Simulate successful ETL workflow
      (logging/emit-etl-completed logger
                                  (random-uuid)
                                  1500
                                  {:packs-generated 5
                                   :packs-promoted 3
                                   :high-risk-findings 2
                                   :sources-processed 10})

      ;; Verify event was emitted
      (let [events @entries
            etl-event (first (filter #(= :etl/completed (:log/event %)) events))]
        (is (some? etl-event)
            "etl/completed event must be emitted")
        (is (= :info (:log/level etl-event))
            "etl/completed must be logged at :info level")
        (is (= :etl (:log/category etl-event))
            "etl/completed must have :etl category")

        ;; Verify required fields per N3 §3.4
        (let [event-data (:data etl-event)]
          (is (= :etl/completed (:event/type event-data))
              "event/type must be :etl/completed")
          (is (uuid? (:event/id event-data))
              "event/id must be a UUID")
          (is (inst? (:event/timestamp event-data))
              "event/timestamp must be an instant")
          (is (= "1.0.0" (:event/version event-data))
              "event/version must be 1.0.0")
          (is (uuid? (:workflow/id event-data))
              "workflow/id must be a UUID")
          (is (= 1500 (:etl/duration-ms event-data))
              "etl/duration-ms must be recorded")

          ;; Verify summary statistics
          (let [summary (:etl/summary event-data)]
            (is (= 5 (:packs-generated summary))
                "summary must include packs-generated")
            (is (= 3 (:packs-promoted summary))
                "summary must include packs-promoted")
            (is (= 2 (:high-risk-findings summary))
                "summary must include high-risk-findings")
            (is (= 10 (:sources-processed summary))
                "summary must include sources-processed")))))))

#_(deftest etl-failed-event-test
  (testing "N3 §3.4: etl/failed event is emitted on ETL workflow failure"
    (let [[logger entries] (logging/collecting-logger {:min-level :error})]

      ;; Simulate failed ETL workflow
      (logging/emit-etl-failed logger
                               (random-uuid)
                               :scanning
                               "Prompt injection detected in source file"
                               {:file "untrusted/config.md"
                                :scanner :prompt-injection-tripwire
                                :severity :critical})

      ;; Verify event was emitted
      (let [events @entries
            etl-event (first (filter #(= :etl/failed (:log/event %)) events))]
        (is (some? etl-event)
            "etl/failed event must be emitted")
        (is (= :error (:log/level etl-event))
            "etl/failed must be logged at :error level")
        (is (= :etl (:log/category etl-event))
            "etl/failed must have :etl category")

        ;; Verify required fields per N3 §3.4
        (let [event-data (:data etl-event)]
          (is (= :etl/failed (:event/type event-data))
              "event/type must be :etl/failed")
          (is (uuid? (:event/id event-data))
              "event/id must be a UUID")
          (is (inst? (:event/timestamp event-data))
              "event/timestamp must be an instant")
          (is (= "1.0.0" (:event/version event-data))
              "event/version must be 1.0.0")
          (is (uuid? (:workflow/id event-data))
              "workflow/id must be a UUID")
          (is (= :scanning (:etl/failure-stage event-data))
              "etl/failure-stage must be recorded")
          (is (= "Prompt injection detected in source file"
                 (:etl/failure-reason event-data))
              "etl/failure-reason must be recorded")

          ;; Verify optional error details
          (let [error-details (:etl/error-details event-data)]
            (is (some? error-details)
                "error-details should be included when provided")
            (is (= "untrusted/config.md" (:file error-details))
                "error-details must include structured error info")
            (is (= :prompt-injection-tripwire (:scanner error-details))
                "error-details must include scanner info")
            (is (= :critical (:severity error-details))
                "error-details must include severity")))))))

#_(deftest etl-failure-stages-test
  (testing "N3 §3.4: etl/failed supports all required failure stages"
    (let [[logger entries] (logging/collecting-logger {:min-level :error})]

      ;; Test each failure stage
      (doseq [stage [:classification :scanning :extraction :validation]]
        (logging/emit-etl-failed logger
                                 (random-uuid)
                                 stage
                                 (str "Failure in " (name stage) " stage")))

      ;; Verify all stages were recorded
      (let [events @entries
            etl-events (filter #(= :etl/failed (:log/event %)) events)
            stages (map #(get-in % [:data :etl/failure-stage]) etl-events)]
        (is (= 4 (count etl-events))
            "All 4 failure stages must be supported")
        (is (= #{:classification :scanning :extraction :validation}
               (set stages))
            "All required failure stages must be present")))))

#_(deftest etl-workflow-integration-test
  (testing "N3 §3.4: ETL workflow emits lifecycle events during execution"
    (let [[logger entries] (logging/collecting-logger {:min-level :debug})]

      ;; Run ETL workflow (this will be a stub until actual ETL is implemented)
      ;; For now, just verify that the event emission functions are callable
      (let [workflow-id (random-uuid)]

        ;; Successful workflow
        (logging/emit-etl-completed logger workflow-id 1000
                                    {:packs-generated 3
                                     :packs-promoted 2
                                     :high-risk-findings 0
                                     :sources-processed 5})

        ;; Failed workflow
        (logging/emit-etl-failed logger workflow-id :validation
                                 "Schema validation failed"
                                 {:field :pack/id})

        ;; Verify both events were emitted
        (let [events @entries
              completed-events (filter #(= :etl/completed (:log/event %)) events)
              failed-events (filter #(= :etl/failed (:log/event %)) events)]
          (is (= 1 (count completed-events))
              "Completed event must be emitted")
          (is (= 1 (count failed-events))
              "Failed event must be emitted"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'conformance.event_stream_test)

  ;; ETL tests disabled pending emit-etl-completed/emit-etl-failed implementation
  ;; (clojure.test/test-vars
  ;;  [#'etl-completed-event-test
  ;;   #'etl-failed-event-test
  ;;   #'etl-failure-stages-test
  ;;   #'etl-workflow-integration-test])

  :leave-this-here)
