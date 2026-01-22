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

(ns ai.miniforge.workflow.configurable
  "DAG-based workflow execution using configurable workflows.
   Executes workflows based on EDN configurations."
  (:require
   [ai.miniforge.workflow.state :as state]))

;------------------------------------------------------------------------------ Layer 0
;; Phase lookup

(defn find-phase
  "Find phase configuration by ID in workflow.

   Arguments:
   - workflow: Workflow configuration
   - phase-id: Phase identifier

   Returns phase config map or nil if not found."
  [workflow phase-id]
  (first (filter #(= phase-id (:phase/id %))
                 (:workflow/phases workflow))))

;------------------------------------------------------------------------------ Layer 1
;; Phase transition logic

(defn select-next-phase
  "Select next phase based on current phase result and workflow config.

   Arguments:
   - workflow: Workflow configuration
   - exec-state: Current execution state
   - phase-result: Result of current phase execution

   Returns:
   - Next phase ID
   - :done if workflow should complete
   - nil if no valid transition"
  [workflow exec-state phase-result]
  (let [current-phase-id (:execution/current-phase exec-state)
        current-phase (find-phase workflow current-phase-id)
        success? (:success? phase-result)
        exit-phases (set (:workflow/exit-phases workflow))]

    ;; Check if current phase is an exit phase
    (if (contains? exit-phases current-phase-id)
      :done

      ;; Determine next phase based on success/failure
      (let [transition (if success?
                         (:phase/on-success current-phase)
                         (:phase/on-failure current-phase))
            target (:transition/target transition)]

        ;; Return target or :done if no transition defined
        (or target :done)))))

;------------------------------------------------------------------------------ Layer 2
;; Phase execution (stub implementation)

(defn execute-configurable-phase
  "Execute a single phase of a configurable workflow.

   For now, this is a stub implementation that returns success.
   In the future, this will:
   - Invoke the appropriate agent based on phase/agent-type
   - Use loop/run-simple for inner loop execution
   - Evaluate gates using policy/evaluate-gates

   Arguments:
   - phase: Phase configuration
   - exec-state: Current execution state
   - context: Execution context

   Returns phase result map:
   {:success? boolean
    :artifacts []
    :errors []
    :metrics {}}"
  [phase _exec-state _context]
  (let [phase-id (:phase/id phase)
        phase-name (:phase/name phase)
        agent-type (:phase/agent-type phase)
        gates (:phase/gates phase [])]

    ;; Stub implementation - always succeeds for now
    ;; In future: invoke agent, run loop, evaluate gates
    (cond
      ;; Special case: done phase
      (= :none agent-type)
      {:success? true
       :artifacts []
       :errors []
       :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}}

      ;; Normal phases - stub success
      :else
      {:success? true
       :artifacts [{:artifact/id (random-uuid)
                    :artifact/type (keyword (name phase-id))
                    :artifact/content {:phase phase-id
                                       :name phase-name
                                       :stub true}
                    :artifact/metadata {:phase phase-id
                                        :agent-type agent-type
                                        :gates-count (count gates)}}]
       :errors []
       :metrics {:tokens 100  ; Stub token count
                 :cost-usd 0.01  ; Stub cost
                 :duration-ms 1000}})))  ; Stub duration

;------------------------------------------------------------------------------ Layer 3
;; Workflow execution

(defn- execute-phase-step
  "Execute a single phase step in the workflow.
   Returns updated execution state or [:continue new-state] to continue loop."
  [workflow exec-state phase context callbacks exit-phases]
  (let [{:keys [on-phase-start on-phase-complete]} callbacks
        current-phase-id (:execution/current-phase exec-state)]

    ;; Invoke phase start callback
    (when on-phase-start
      (on-phase-start exec-state phase))

    ;; Execute phase and record result
    (let [phase-result (execute-configurable-phase phase exec-state context)
          exec-state' (state/record-phase-result exec-state current-phase-id phase-result)]

      ;; Invoke phase complete callback
      (when on-phase-complete
        (on-phase-complete exec-state' phase phase-result))

      ;; Determine next action based on exit phase or transitions
      (cond
        ;; At exit phase - complete workflow
        (contains? exit-phases current-phase-id)
        (state/mark-completed exec-state')

        ;; Determine next phase
        :else
        (let [next-phase-id (select-next-phase workflow exec-state' phase-result)]
          (if next-phase-id
            [:continue (state/transition-to-phase exec-state' next-phase-id :advance)]
            (state/mark-failed exec-state'
                               {:type :no-valid-transition
                                :message (str "No valid transition from phase: " current-phase-id)})))))))

(defn run-configurable-workflow
  "Execute a complete configurable workflow.

   Arguments:
   - workflow: Workflow configuration
   - input: Input data for the workflow
   - context: Execution context
     - :max-phases - Max phases to execute (default 50, safety limit)
     - :on-phase-start - Callback fn [exec-state phase]
     - :on-phase-complete - Callback fn [exec-state phase result]

   Returns final execution state.

   The workflow executes as follows:
   1. Create initial execution state
   2. Loop through phases:
      a. Get current phase config
      b. Execute phase
      c. Record result
      d. Determine next phase
      e. Transition to next phase
   3. Mark as completed or failed"
  [workflow input context]
  (let [max-phases (or (:max-phases context) 50)
        callbacks {:on-phase-start (:on-phase-start context)
                   :on-phase-complete (:on-phase-complete context)}
        exit-phases (set (:workflow/exit-phases workflow))]

    (loop [exec-state (state/create-execution-state workflow input)
           phase-count 0]

      ;; Early exit: max phases exceeded
      (cond
        (>= phase-count max-phases)
        (state/mark-failed exec-state
                           {:type :max-phases-exceeded
                            :message (str "Exceeded maximum phase count: " max-phases)})

        ;; Execute phase step
        :else
        (let [current-phase-id (:execution/current-phase exec-state)
              phase (find-phase workflow current-phase-id)]

          (if-not phase
            ;; Phase not found - fail workflow
            (state/mark-failed exec-state
                               {:type :phase-not-found
                                :message (str "Phase not found: " current-phase-id)})

            ;; Execute phase step
            (let [result (execute-phase-step workflow exec-state phase context callbacks exit-phases)]
              (if (vector? result)
                ;; [:continue new-state] - continue to next phase
                (recur (second result) (inc phase-count))
                ;; Terminal state (completed or failed) - return it
                result))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.workflow.loader :as loader])

  ;; Load workflow
  (def workflow-result (loader/load-workflow :simple-test-v1 "1.0.0" {}))
  (def workflow (:workflow workflow-result))

  ;; Find phase
  (find-phase workflow :plan)

  ;; Execute workflow
  (def exec-state
    (run-configurable-workflow workflow {:task "Test task"} {}))

  ;; Check state
  (:execution/status exec-state)
  (:execution/current-phase exec-state)
  (:execution/phase-results exec-state)
  (:execution/artifacts exec-state)
  (:execution/metrics exec-state)

  ;; With callbacks
  (def exec-state2
    (run-configurable-workflow
     workflow
     {:task "Test task"}
     {:on-phase-start (fn [_state phase]
                        (println "Starting phase:" (:phase/id phase)))
      :on-phase-complete (fn [_state phase result]
                           (println "Completed phase:" (:phase/id phase)
                                    "Success:" (:success? result)))}))

  :end)
