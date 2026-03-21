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

(ns ai.miniforge.workflow.state
  "Execution state management for workflow runs.

   Uses a formal FSM (see fsm.clj) for state transitions.
   Tracks runtime state separately from workflow configuration."
  (:require
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.fsm :as fsm]))

;------------------------------------------------------------------------------ Layer 0
;; State creation

(defn create-execution-state
  "Create initial execution state for a workflow run.

   Arguments:
   - workflow: Workflow configuration map
   - input: Input data/context for the workflow

   Returns execution state map with:
   - :execution/id - Unique execution ID
   - :execution/workflow-id - Reference to workflow config
   - :execution/workflow-version - Workflow version
   - :execution/status - Current status (:pending, :running, :completed, :failed)
   - :execution/current-phase - Current phase ID
   - :execution/phase-results - Map of phase-id -> result
   - :execution/artifacts - Vector of artifacts produced
   - :execution/errors - Vector of errors encountered
   - :execution/metrics - Accumulated metrics
   - :execution/history - Vector of state transitions
   - :execution/input - Original input data
   - :execution/created-at - Timestamp
   - :execution/updated-at - Timestamp"
  [workflow input]
  (let [entry-phase (-> workflow :workflow/phases first :phase/id)]
    {:execution/id (random-uuid)
     :execution/workflow-id (:workflow/id workflow)
     :execution/workflow-version (:workflow/version workflow)
     :execution/status :pending
     :execution/current-phase entry-phase
     :execution/phase-results {}
     :execution/artifacts []
     :execution/errors []
     :execution/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}
     :execution/history []
     :execution/input input
     :execution/created-at (System/currentTimeMillis)
     :execution/updated-at (System/currentTimeMillis)}))

;------------------------------------------------------------------------------ Layer 1
;; FSM-based state transitions

(defn record-fsm-transition
  "Record an FSM state transition in history."
  [state from-status to-status event]
  (let [transition {:from-status from-status
                    :to-status to-status
                    :event event
                    :timestamp (System/currentTimeMillis)}]
    (-> state
        (update :execution/history conj transition)
        (assoc :execution/updated-at (System/currentTimeMillis)))))

(defn record-phase-transition
  "Record a phase transition in history."
  [state from-phase to-phase reason]
  (let [transition {:from-phase from-phase
                    :to-phase to-phase
                    :reason reason
                    :timestamp (System/currentTimeMillis)}]
    (-> state
        (update :execution/history conj transition)
        (assoc :execution/updated-at (System/currentTimeMillis)))))

(defn transition-status
  "Transition workflow status using FSM.

   Arguments:
   - state: Current execution state
   - event: Event keyword (:start, :complete, :fail, :pause, :resume, :cancel)

   Returns:
   - Updated state if transition valid
   - Throws ex-info if transition invalid"
  [state event]
  (let [current-status (:execution/status state)
        result (fsm/transition current-status event)]
    (if (fsm/succeeded? result)
      (-> state
          (assoc :execution/status (:state result))
          (record-fsm-transition current-status (:state result) event))
      (throw (ex-info "Invalid state transition"
                     {:current-status current-status
                      :event event
                      :error (:error result)
                      :message (:message result)})))))

(defn transition-to-phase
  "Transition execution to a new phase.

   Arguments:
   - state: Current execution state
   - phase-id: Target phase ID
   - reason: Reason for transition (optional)

   Returns updated state."
  ([state phase-id]
   (transition-to-phase state phase-id :advance))
  ([state phase-id reason]
   (let [current-phase (:execution/current-phase state)
         ;; Ensure workflow is running when transitioning phases
         state-with-status (if (= :pending (:execution/status state))
                            (transition-status state :start)
                            state)]
     (-> state-with-status
         (assoc :execution/current-phase phase-id)
         (record-phase-transition current-phase phase-id reason)))))

;------------------------------------------------------------------------------ Layer 2
;; Phase result recording

(defn record-phase-result
  "Record the result of a phase execution.

   Arguments:
   - state: Current execution state
   - phase-id: Phase that was executed
   - result: Phase result map {:success? :artifacts :errors :metrics}

   Returns updated state with:
   - Phase result recorded
   - Artifacts added
   - Errors added
   - Metrics accumulated"
  [state phase-id result]
  (-> state
      (assoc-in [:execution/phase-results phase-id] result)
      (update :execution/artifacts into (:artifacts result []))
      (update :execution/errors into (:errors result []))
      (update :execution/metrics
              (fn [m]
                (merge-with + m (:metrics result {:tokens 0 :cost-usd 0.0 :duration-ms 0}))))
      (assoc :execution/updated-at (System/currentTimeMillis))))

;------------------------------------------------------------------------------ Layer 3
;; Completion and failure

(defn mark-completed
  "Mark execution as completed using FSM transition.

   Returns updated state with:
   - Status transitioned to :completed via FSM
   - Completed timestamp added"
  [state]
  (let [;; Ensure workflow is running before completing
        state-with-running (if (= :pending (:execution/status state))
                            (transition-status state :start)
                            state)]
    (-> state-with-running
        (transition-status :complete)
        (assoc :execution/completed-at (System/currentTimeMillis)))))

(defn mark-failed
  "Mark execution as failed using FSM transition.

   Arguments:
   - state: Current execution state
   - error: Error map or string

   Returns updated state with:
   - Status transitioned to :failed via FSM
   - Error added to errors list
   - Failed timestamp added"
  [state error]
  (let [error-map (if (string? error)
                    {:type :execution-failed
                     :message error}
                    error)
        ;; Ensure workflow is running before failing
        state-with-running (if (= :pending (:execution/status state))
                            (transition-status state :start)
                            state)]
    (-> state-with-running
        (transition-status :fail)
        (update :execution/errors conj error-map)
        (assoc :execution/failed-at (System/currentTimeMillis)))))

;------------------------------------------------------------------------------ Layer 4
;; State queries

(defn completed?
  "Check if execution is completed."
  [state]
  (phase/succeeded? state))

(defn failed?
  "Check if execution has failed."
  [state]
  (phase/failed? state))

(defn running?
  "Check if execution is running."
  [state]
  (= :running (:execution/status state)))

(defn terminal?
  "Check if execution is in a terminal state (no further transitions).

   Returns true for :completed, :failed, or :cancelled states."
  [state]
  (fsm/terminal-state? (:execution/status state)))

(defn can-transition?
  "Check if a status transition is valid from current state.

   Arguments:
   - state: Current execution state
   - event: Event keyword to attempt

   Returns: boolean"
  [state event]
  (fsm/valid-transition? (:execution/status state) event))

(defn get-phase-result
  "Get the result of a specific phase."
  [state phase-id]
  (get-in state [:execution/phase-results phase-id]))

(defn has-phase-result?
  "Check if a phase has been executed."
  [state phase-id]
  (contains? (:execution/phase-results state) phase-id))

(defn get-current-metrics
  "Get current accumulated metrics."
  [state]
  (:execution/metrics state))

(defn get-duration-ms
  "Get total execution duration in milliseconds."
  [state]
  (let [start (:execution/created-at state)
        end (or (:execution/completed-at state)
                (:execution/failed-at state)
                (:execution/updated-at state))]
    (- end start)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create execution state
  (def sample-workflow
    {:workflow/id :test-workflow
     :workflow/version "1.0.0"
     :workflow/entry-phase :start
     :workflow/exit-phases [:end]})

  (def exec-state
    (create-execution-state sample-workflow {:input "test"}))

  ;; Transition to new phase
  (def exec-state2
    (transition-to-phase exec-state :implement))

  ;; Record phase result
  (def exec-state3
    (record-phase-result exec-state2 :implement
                         {:success? true
                          :artifacts [{:type :code :content "..."}]
                          :errors []
                          :metrics {:tokens 100}}))

  ;; Check phase results
  (has-phase-result? exec-state3 :implement)
  (get-phase-result exec-state3 :implement)

  ;; Mark completed
  (def exec-state4 (mark-completed exec-state3))
  (completed? exec-state4)

  ;; Get duration
  (get-duration-ms exec-state4)

  :end)
