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
   [ai.miniforge.workflow.agent-factory :as factory]
   [ai.miniforge.workflow.configurable-defaults :as defaults]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.schema.interface :as schema]
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

(defn- first-transition-target
  [phase]
  (get-in phase [:phase/next 0 :target]))

(defn- phase->pipeline-entry
  [phase]
  (let [target-phase (first-transition-target phase)]
    (cond-> {:phase (:phase/id phase)}
      target-phase (assoc :on-success target-phase))))

(defn- ensure-execution-pipeline
  [workflow]
  (if (:workflow/pipeline workflow)
    workflow
    (assoc workflow
           :workflow/pipeline
           (mapv phase->pipeline-entry
                 (:workflow/phases workflow)))))

(defn- merged-phase-metrics
  [metrics]
  (merge (defaults/default-phase-metrics) metrics))

(defn- configurable-phase-result
  [success? artifacts errors metrics]
  {:success? success?
   :artifacts artifacts
   :errors errors
   :metrics (merged-phase-metrics metrics)})

(defn- successful-phase-result
  [artifacts metrics]
  (configurable-phase-result true artifacts [] metrics))

(defn- phase-error
  [error-type message]
  {:type error-type
   :message message})

(defn- failed-phase-result
  ([error-type message]
   (failed-phase-result error-type message {}))
  ([error-type message {:keys [metrics]}]
   (configurable-phase-result false
                              []
                              [(phase-error error-type message)]
                              metrics)))

(defn- missing-phase-handler-message
  [handler-key]
  (messages/t :configurable/missing-phase-handler
              {:handler-key handler-key}))

(defn- phase-execution-failure-message
  [error]
  (messages/t :configurable/phase-execution-failed
              {:error error}))

(defn- missing-phase-message
  [phase-id]
  (messages/t :configurable/phase-not-found
              {:phase phase-id}))

(defn- dag-execution-failed-message
  []
  (messages/t :configurable/dag-execution-failed))

(defn- inner-loop-succeeded?
  [result]
  (true? (:success result)))

(defn- terminal-status?
  [exec-state]
  (contains? #{:completed :failed :cancelled}
             (:execution/status exec-state)))

(defn- dag-disabled?
  [context]
  (true? (get context :disable-dag-parallelization false)))

(defn- phase-order
  [workflow]
  (mapv :phase/id (:workflow/phases workflow)))

(defn- phase-index
  [workflow phase-id]
  (some (fn [[idx current-phase]]
          (when (= current-phase phase-id)
            idx))
        (map-indexed vector (phase-order workflow))))

(defn- last-recorded-phase
  [exec-state]
  (let [recorded-phases (keys (:execution/phase-results exec-state))
        ordered-phases (phase-order (:execution/workflow exec-state))]
    (last (filter (set recorded-phases) ordered-phases))))

(defn- project-terminal-phase
  [exec-state]
  (let [phase-id (or (:execution/current-phase exec-state)
                     (last-recorded-phase exec-state))
        projected-index (phase-index (:execution/workflow exec-state) phase-id)]
    (cond-> exec-state
      phase-id (assoc :execution/current-phase phase-id)
      (some? projected-index) (assoc :execution/phase-index projected-index))))

(defn- phase-transition-entry
  [from-phase to-phase reason]
  {:from-phase from-phase
   :to-phase to-phase
   :reason reason
   :timestamp (System/currentTimeMillis)})

(defn- record-phase-transition
  [exec-state from-phase to-phase reason]
  (update exec-state :execution/history conj
          (phase-transition-entry from-phase to-phase reason)))

(defn- transition-phase
  [exec-state event reason]
  (let [prior-machine-state (:execution/fsm-state exec-state)
        from-phase (:execution/current-phase exec-state)
        transitioned (ctx/transition-execution exec-state event)
        next-phase (:execution/current-phase transitioned)
        state-changed? (not= prior-machine-state
                             (:execution/fsm-state transitioned))]
    (cond-> transitioned
      (and state-changed?
           from-phase
           next-phase
           (not= from-phase next-phase))
      (record-phase-transition from-phase next-phase reason))))

(defn- append-execution-error
  [exec-state error-type message extra-data]
  (update exec-state :execution/errors conj
          (merge {:type error-type
                  :message message}
                 extra-data)))

(defn- fail-execution
  ([exec-state error-type message]
   (fail-execution exec-state error-type message {}))
  ([exec-state error-type message extra-data]
   (-> exec-state
       (append-execution-error error-type message extra-data)
       (ctx/transition-to-failed)
       (project-terminal-phase))))

(defn- initialize-execution
  [workflow input context]
  (assoc (ctx/create-context workflow input context)
         :execution/history []))

(defn- record-phase-result
  [exec-state phase-id phase-result]
  (let [phase-metrics (:metrics phase-result)]
    (-> exec-state
        (assoc-in [:execution/phase-results phase-id] phase-result)
        (update :execution/artifacts into (get phase-result :artifacts []))
        (update :execution/errors into (get phase-result :errors []))
        (update :execution/metrics ctx/merge-metrics phase-metrics))))

(defn- configurable-phase-event
  [phase-result]
  (if (schema/succeeded? phase-result)
    :phase/succeed
    :phase/fail))

;------------------------------------------------------------------------------ Layer 2
;; Phase execution (real implementation)

(defn execute-handler-phase
  "Execute a phase using a caller-provided handler from :phase-handlers context."
  [phase exec-state context]
  (let [handler-key (:phase/handler phase)
        handler (get (:phase-handlers context) handler-key)]
    (if handler
      (handler phase exec-state context)
      (failed-phase-result :missing-phase-handler
                           (missing-phase-handler-message handler-key)))))

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
        max-iterations (get inner-loop
                            :max-iterations
                            (defaults/default-inner-loop-iterations))]

    (cond
      (:phase/handler phase)
      (execute-handler-phase phase exec-state context)

      ;; Skip :none agent
      (= :none phase-agent)
      (successful-phase-result [] (defaults/default-phase-metrics))

      ;; Real execution
      :else
      (let [phase-agent-instance (factory/create-agent-for-phase phase context)
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
                        :error (phase-execution-failure-message
                                (.getMessage e))
                        :metrics (defaults/default-phase-metrics)}))]

        (if (inner-loop-succeeded? result)
          ;; Success - build standard artifact
          (let [raw-artifact (:artifact result)
                metrics (:metrics result {})
                standard-artifact (factory/build-artifact-for-phase raw-artifact phase metrics)]
            (successful-phase-result [standard-artifact] metrics))

          ;; Failure - return error
          (failed-phase-result :phase-failed
                               (get result
                                    :error
                                    (messages/t :configurable/default-phase-error))
                               {:metrics (:metrics result)}))))))

;------------------------------------------------------------------------------ Layer 3
;; Workflow execution

(defn extract-plan-from-result
  "Extract plan artifact from phase result if present."
  [phase-result]
  (when-let [artifacts (:artifacts phase-result)]
    (first (filter #(or (:plan/id %)
                        (= :plan (:artifact/type %)))
                   artifacts))))

(defn execute-dag-for-plan
  "Execute plan tasks via DAG orchestrator.
   Returns updated exec-state with DAG results merged.
   Each DAG task produces its own PR; stores :execution/dag-pr-infos."
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
        pre-completed-artifacts (get-in exec-state [:execution/opts :pre-completed-artifacts] [])
        dag-context (assoc dag-context :pre-completed-ids
                          (or (get-in exec-state [:execution/opts :pre-completed-dag-tasks])
                              (get-in context [:pre-completed-dag-tasks] #{})))
        dag-result (dag-orch/execute-plan-as-dag plan dag-context)
        pr-infos (:pr-infos dag-result)]

    ;; Merge DAG results + recovered artifacts into execution state
    (cond-> (-> exec-state
                (update :execution/artifacts into (concat pre-completed-artifacts
                                                          (:artifacts dag-result [])))
                (update :execution/metrics
                        (fn [m]
                          (merge-with + m (merged-phase-metrics (:metrics dag-result)))))
                (assoc-in [:execution/phase-results :dag-execution] dag-result)
                (assoc :execution/dag-result dag-result))
      (seq pr-infos) (assoc :execution/dag-pr-infos pr-infos))))

(defn- continue-execution
  [exec-state]
  [:continue exec-state])

(defn- dag-implement-phase-result
  [dag-result]
  (let [artifacts (get dag-result :artifacts [])
        metrics (get dag-result :metrics {})]
    (successful-phase-result artifacts metrics)))

(defn- record-dag-implement-result
  [exec-state dag-result]
  (assoc-in exec-state
            [:execution/phase-results :implement]
            (dag-implement-phase-result dag-result)))

(defn- continue-from-dag-success
  [exec-state]
  (let [dag-result (:execution/dag-result exec-state)
        after-plan (transition-phase exec-state :phase/succeed :dag-complete)
        next-state (if (= :implement (:execution/current-phase after-plan))
                     (-> after-plan
                         (record-dag-implement-result dag-result)
                         (transition-phase :phase/succeed :dag-complete))
                     after-plan)]
    (if (terminal-status? next-state)
      (project-terminal-phase next-state)
      (continue-execution next-state))))

(defn execute-phase-step
  "Execute a single phase step in the workflow.
   Returns updated execution state or [:continue new-state] to continue loop.

   After plan phase, checks if the plan has parallelizable tasks and
   automatically delegates to DAG executor if so."
  [_workflow exec-state phase context callbacks]
  (let [{:keys [on-phase-start on-phase-complete]} callbacks
        current-phase-id (:execution/current-phase exec-state)]

    ;; Invoke phase start callback
    (when on-phase-start
      (on-phase-start exec-state phase))

    ;; Execute phase and record result
    (let [phase-result (execute-configurable-phase phase exec-state context)
          exec-state' (record-phase-result exec-state current-phase-id phase-result)]

      ;; Invoke phase complete callback
      (when on-phase-complete
        (on-phase-complete exec-state' phase phase-result))

      ;; After plan phase, check for parallelization opportunity
      (let [is-plan-phase? (= :plan current-phase-id)
            plan (when is-plan-phase? (extract-plan-from-result phase-result))
            should-parallelize? (and plan
                                     (schema/succeeded? phase-result)
                                     (dag-orch/parallelizable-plan? plan)
                                     (not (dag-disabled? context)))]

        (if should-parallelize?
          ;; Execute plan via DAG — each task produces its own PR
          (let [exec-state-with-dag (execute-dag-for-plan exec-state' plan context callbacks)
                dag-result (:execution/dag-result exec-state-with-dag)]
            (if (schema/succeeded? dag-result)
              (continue-from-dag-success exec-state-with-dag)
              (fail-execution exec-state-with-dag
                              :dag-execution-failed
                              (dag-execution-failed-message)
                              {:dag-result dag-result})))

          ;; Normal flow - transition according to the compiled execution machine
          (let [next-state (transition-phase exec-state'
                                             (configurable-phase-event phase-result)
                                             :advance)]
            (cond
              (terminal-status? next-state)
              (project-terminal-phase next-state)

              :else
              (continue-execution next-state))))))))

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
      d. Apply the outcome through the authoritative execution machine
   3. Mark as completed or failed"
  [workflow input context]
  (let [execution-workflow (ensure-execution-pipeline workflow)
        max-phases (get context :max-phases (defaults/max-phases))
        callbacks {:on-phase-start (:on-phase-start context)
                   :on-phase-complete (:on-phase-complete context)}]

    (loop [exec-state (initialize-execution execution-workflow input context)
           phase-count 0]

      ;; Early exit: max phases exceeded
      (cond
        (>= phase-count max-phases)
        (fail-execution exec-state
                        :max-phases-exceeded
                        (messages/t :status/max-phases
                                    {:max-phases max-phases}))

        (terminal-status? exec-state)
        (project-terminal-phase exec-state)

        ;; Execute phase step
        :else
        (let [current-phase-id (:execution/current-phase exec-state)
              phase (find-phase execution-workflow current-phase-id)]

          (if-not phase
            ;; Phase not found - fail workflow
            (fail-execution exec-state
                            :phase-not-found
                            (missing-phase-message current-phase-id))

            ;; Execute phase step
            (let [result (execute-phase-step execution-workflow
                                             exec-state
                                             phase
                                             context
                                             callbacks)]
              (if (vector? result)
                ;; [:continue new-state] - continue to next phase
                (recur (second result) (inc phase-count))
                ;; Terminal state (completed or failed) - return it
                (project-terminal-phase result)))))))))

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
