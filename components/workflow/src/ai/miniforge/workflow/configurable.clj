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

(ns ai.miniforge.workflow.configurable
  "DAG-based workflow execution using configurable workflows.
   Executes workflows based on EDN configurations.

   Supports automatic task parallelization:
   When the plan phase produces multiple parallelizable tasks,
   the workflow automatically delegates to the DAG executor
   for parallel execution."
  (:require
   [ai.miniforge.workflow.state :as state]
   [ai.miniforge.workflow.agent-factory :as factory]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.loop.interface :as loop]))

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
  [workflow exec-state _phase-result]
  (let [current-phase-id (:execution/current-phase exec-state)
        current-phase (find-phase workflow current-phase-id)
        next-transitions (:phase/next current-phase [])]

    ;; Check if no transitions defined (exit phase)
    (if (empty? next-transitions)
      :done

      ;; For now, use first transition
      ;; Future: evaluate conditions and probabilities
      (let [transition (first next-transitions)
            target (:target transition)]

        ;; Return target or :done if no target defined
        (or target :done)))))

;------------------------------------------------------------------------------ Layer 2
;; Phase execution (real implementation)

(defn execute-configurable-phase
  "Execute a single phase of a configurable workflow.

   This implementation:
   - Invokes the appropriate agent based on phase/agent-type
   - Uses loop/run-simple for inner loop execution
   - Evaluates gates for validation

   Arguments:
   - phase: Phase configuration
   - exec-state: Current execution state
   - context: Execution context with :llm-backend

   Returns phase result map:
   {:success? boolean
    :artifacts []
    :errors []
    :metrics {}}"
  [phase exec-state context]
  (let [phase-agent (:phase/agent phase)
        inner-loop (:phase/inner-loop phase {})
        max-iterations (or (:max-iterations inner-loop) 5)]

    (cond
      ;; Skip :none agent
      (= :none phase-agent)
      {:success? true
       :artifacts []
       :errors []
       :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}}

      ;; Real execution
      :else
      (let [phase-agent-instance (factory/create-agent-for-phase phase context)
            _gates (factory/create-gates-for-phase phase context)
            task (factory/create-task-for-phase phase exec-state context)
            generate-fn (factory/create-generate-fn phase-agent-instance context)
            repair-fn (factory/create-repair-fn phase-agent-instance context)

            loop-context (merge context
                                {:max-iterations max-iterations
                                 :repair-fn repair-fn})

            ;; Run inner loop with generate/validate/repair
            result (try
                     (loop/run-simple task generate-fn loop-context)
                     (catch Exception e
                       {:success false
                        :error (str "Phase execution failed: " (.getMessage e))
                        :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}}))]

        (if (:success result)
          ;; Success - build standard artifact
          (let [raw-artifact (:artifact result)
                metrics (:metrics result {})
                standard-artifact (factory/build-artifact-for-phase raw-artifact phase metrics)]
            {:success? true
             :artifacts [standard-artifact]
             :errors []
             :metrics metrics})

          ;; Failure - return error
          {:success? false
           :artifacts []
           :errors [{:type :phase-failed
                     :phase (:phase/id phase)
                     :message (or (:error result) "Phase execution failed")}]
           :metrics (:metrics result {})})))))

;------------------------------------------------------------------------------ Layer 3
;; Workflow execution

(defn- extract-plan-from-result
  "Extract plan artifact from phase result if present."
  [phase-result]
  (when-let [artifacts (:artifacts phase-result)]
    (first (filter #(or (:plan/id %)
                        (= :plan (:artifact/type %)))
                   artifacts))))

(defn- execute-dag-for-plan
  "Execute plan tasks via DAG orchestrator.
   Returns updated exec-state with DAG results merged."
  [exec-state plan context callbacks]
  (let [{:keys [on-phase-start on-phase-complete]} callbacks
        dag-context (merge context
                           {:workflow-id (:execution/id exec-state)
                            :on-task-start (fn [task-id]
                                             (when on-phase-start
                                               (on-phase-start exec-state
                                                               {:phase/id :dag-task
                                                                :task-id task-id})))
                            :on-task-complete (fn [task-id result]
                                                (when on-phase-complete
                                                  (on-phase-complete exec-state
                                                                     {:phase/id :dag-task
                                                                      :task-id task-id}
                                                                     result)))})
        ;; Pass pre-completed task IDs for resume support
        dag-context (cond-> dag-context
                      (get-in exec-state [:execution/opts :pre-completed-dag-tasks])
                      (assoc :pre-completed-ids
                             (get-in exec-state [:execution/opts :pre-completed-dag-tasks])))
        dag-result (dag-orch/execute-plan-as-dag plan dag-context)]

    ;; Merge DAG results into execution state
    (-> exec-state
        (update :execution/artifacts into (:artifacts dag-result []))
        (update :execution/metrics
                (fn [m]
                  (merge-with + m (:metrics dag-result {:tokens 0 :cost-usd 0.0}))))
        (assoc-in [:execution/phase-results :dag-execution] dag-result)
        (assoc :execution/dag-result dag-result))))

(defn- execute-phase-step
  "Execute a single phase step in the workflow.
   Returns updated execution state or [:continue new-state] to continue loop.

   After plan phase, checks if the plan has parallelizable tasks and
   automatically delegates to DAG executor if so."
  [workflow exec-state phase context callbacks]
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

      ;; After plan phase, check for parallelization opportunity
      (let [is-plan-phase? (= :plan current-phase-id)
            plan (when is-plan-phase? (extract-plan-from-result phase-result))
            should-parallelize? (and plan
                                     (:success? phase-result)
                                     (dag-orch/parallelizable-plan? plan)
                                     ;; Allow disabling via context
                                     (not (:disable-dag-parallelization context)))]

        (if should-parallelize?
          ;; Execute plan via DAG, then skip implement phase
          (let [exec-state-with-dag (execute-dag-for-plan exec-state' plan context callbacks)
                dag-success? (get-in exec-state-with-dag [:execution/dag-result :success?])]
            (if dag-success?
              ;; Skip to verify phase (or next phase after implement)
              (let [implement-phase (find-phase workflow :implement)
                    post-implement-transitions (:phase/next implement-phase [])
                    next-after-implement (or (:target (first post-implement-transitions)) :done)]
                (if (= :done next-after-implement)
                  (state/mark-completed exec-state-with-dag)
                  [:continue (state/transition-to-phase exec-state-with-dag
                                                        next-after-implement
                                                        :dag-complete)]))
              ;; DAG failed
              (state/mark-failed exec-state-with-dag
                                 {:type :dag-execution-failed
                                  :message "Parallel task execution failed"
                                  :dag-result (:execution/dag-result exec-state-with-dag)})))

          ;; Normal flow - determine next phase
          (let [next-phase-id (select-next-phase workflow exec-state' phase-result)]
            (cond
              ;; :done means workflow should complete
              (= :done next-phase-id)
              (state/mark-completed exec-state')

              ;; Valid next phase - continue
              next-phase-id
              [:continue (state/transition-to-phase exec-state' next-phase-id :advance)]

              ;; No valid transition
              :else
              (state/mark-failed exec-state'
                                 {:type :no-valid-transition
                                  :message (str "No valid transition from phase: " current-phase-id)}))))))))

(defn run-configurable-workflow
  "Execute a complete configurable workflow.

   Arguments:
   - workflow: Workflow configuration
   - input: Input data for the workflow
   - context: Execution context
     - :llm-backend - LLM backend for agent execution (required)
     - :max-phases - Max phases to execute (default 50, safety limit)
     - :on-phase-start - Callback fn [exec-state phase]
     - :on-phase-complete - Callback fn [exec-state phase result]

   Returns final execution state.

   The workflow executes as follows:
   1. Create initial execution state
   2. Loop through phases:
      a. Get current phase config
      b. Execute phase (invoke agent + inner loop)
      c. Record result
      d. Determine next phase
      e. Transition to next phase
   3. Mark as completed or failed"
  [workflow input context]
  (let [max-phases (or (:max-phases context) 50)
        callbacks {:on-phase-start (:on-phase-start context)
                   :on-phase-complete (:on-phase-complete context)}]

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
            (let [result (execute-phase-step workflow exec-state phase context callbacks)]
              (if (vector? result)
                ;; [:continue new-state] - continue to next phase
                (recur (second result) (inc phase-count))
                ;; Terminal state (completed or failed) - return it
                result))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.workflow.loader :as loader])
  (require '[ai.miniforge.agent.interface :as agent])

  ;; Load workflow
  (def workflow-result (loader/load-workflow :simple-test-v1 "1.0.0" {}))
  (def workflow (:workflow workflow-result))

  ;; Find phase
  (find-phase workflow :plan)

  ;; Execute workflow with mock LLM
  (def mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"}))
  (def exec-state
    (run-configurable-workflow workflow
                               {:task "Test task"}
                               {:llm-backend mock-llm}))

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
     {:llm-backend mock-llm
      :on-phase-start (fn [_state phase]
                        (println "Starting phase:" (:phase/id phase)))
      :on-phase-complete (fn [_state phase result]
                           (println "Completed phase:" (:phase/id phase)
                                    "Success:" (:success? result)))}))

  :end)
