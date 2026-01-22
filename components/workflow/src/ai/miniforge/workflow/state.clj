;; Copyright 2025 miniforge.ai
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
   Tracks runtime state separately from workflow configuration.")

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
;; State transitions

(defn- record-transition
  "Record a state transition in history."
  [state from-phase to-phase reason]
  (let [transition {:from from-phase
                    :to to-phase
                    :reason reason
                    :timestamp (System/currentTimeMillis)}]
    (-> state
        (update :execution/history conj transition)
        (assoc :execution/updated-at (System/currentTimeMillis)))))

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
   (let [current-phase (:execution/current-phase state)]
     (-> state
         (assoc :execution/current-phase phase-id)
         (assoc :execution/status :running)
         (record-transition current-phase phase-id reason)))))

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
  "Mark execution as completed.

   Returns updated state with:
   - Status set to :completed
   - Completed timestamp added"
  [state]
  (-> state
      (assoc :execution/status :completed)
      (assoc :execution/completed-at (System/currentTimeMillis))
      (assoc :execution/updated-at (System/currentTimeMillis))))

(defn mark-failed
  "Mark execution as failed.

   Arguments:
   - state: Current execution state
   - error: Error map or string

   Returns updated state with:
   - Status set to :failed
   - Error added to errors list
   - Failed timestamp added"
  [state error]
  (let [error-map (if (string? error)
                    {:type :execution-failed
                     :message error}
                    error)]
    (-> state
        (assoc :execution/status :failed)
        (update :execution/errors conj error-map)
        (assoc :execution/failed-at (System/currentTimeMillis))
        (assoc :execution/updated-at (System/currentTimeMillis)))))

;------------------------------------------------------------------------------ Layer 4
;; State queries

(defn completed?
  "Check if execution is completed."
  [state]
  (= :completed (:execution/status state)))

(defn failed?
  "Check if execution has failed."
  [state]
  (= :failed (:execution/status state)))

(defn running?
  "Check if execution is running."
  [state]
  (= :running (:execution/status state)))

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
