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

(ns ai.miniforge.workflow.dag-orchestrator
  "Orchestrates multi-task execution via DAG when plan has parallelizable tasks.

   When the planner produces a plan with multiple tasks, this orchestrator:
   1. Analyzes task dependencies to identify parallelization opportunities
   2. Creates a DAG structure for the dag-executor
   3. Dispatches each task as a mini-workflow (implement → verify)
   4. Aggregates results for the parent workflow to continue

   This enables miniforge to automatically decompose large tasks and
   execute independent subtasks in parallel."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.task.interface :as task]))

;------------------------------------------------------------------------------ Layer 0
;; Plan analysis

(defn parallelizable-plan?
  "Check if a plan should be executed via DAG rather than sequentially.

   A plan is parallelizable when:
   - It has more than one task
   - At least two tasks have no dependencies on each other

   Arguments:
   - plan: Plan map from planner with :plan/tasks

   Returns: boolean"
  [plan]
  (let [tasks (:plan/tasks plan [])]
    (when (> (count tasks) 1)
      ;; Check if any tasks can run in parallel (no mutual dependencies)
      (let [task-ids (set (map :task/id tasks))
            deps-map (into {}
                           (map (fn [t]
                                  [(:task/id t) (set (:task/dependencies t []))])
                                tasks))
            ;; Find tasks with no deps (roots)
            roots (filter #(empty? (get deps-map %)) task-ids)]
        ;; Parallelizable if >1 root OR if any task only depends on roots
        (or (> (count roots) 1)
            (some (fn [t]
                    (let [deps (get deps-map (:task/id t))]
                      (and (seq deps)
                           (every? #(empty? (get deps-map %)) deps))))
                  tasks))))))

(defn estimate-parallel-speedup
  "Estimate the speedup factor from parallel execution.

   Arguments:
   - plan: Plan map with :plan/tasks

   Returns: {:parallelizable? bool :max-parallel int :estimated-speedup float}"
  [plan]
  (let [tasks (:plan/tasks plan [])
        task-count (count tasks)
        deps-map (into {}
                       (map (fn [t]
                              [(:task/id t) (set (:task/dependencies t []))])
                            tasks))
        ;; Compute levels (tasks at same level can run in parallel)
        levels (loop [remaining (set (map :task/id tasks))
                      completed #{}
                      level-count 0
                      max-width 0]
                 (if (empty? remaining)
                   {:levels level-count :max-width max-width}
                   (let [ready (filter (fn [id]
                                         (every? completed (get deps-map id #{})))
                                       remaining)
                         width (count ready)]
                     (recur (apply disj remaining ready)
                            (into completed ready)
                            (inc level-count)
                            (max max-width width)))))]
    {:parallelizable? (> (:max-width levels) 1)
     :task-count task-count
     :max-parallel (:max-width levels)
     :levels (:levels levels)
     :estimated-speedup (if (> (:levels levels) 0)
                          (float (/ task-count (:levels levels)))
                          1.0)}))

;------------------------------------------------------------------------------ Layer 1
;; Plan to DAG conversion

(defn plan->dag-tasks
  "Convert plan tasks to DAG task definitions.

   Arguments:
   - plan: Plan map with :plan/tasks
   - context: Execution context for task metadata

   Returns: Vector of DAG task definitions for dag-executor"
  [plan context]
  (let [tasks (:plan/tasks plan [])]
    (mapv (fn [task]
            {:task/id (:task/id task)
             :task/deps (set (:task/dependencies task []))
             :task/description (:task/description task)
             :task/type (:task/type task :implement)
             :task/acceptance-criteria (:task/acceptance-criteria task [])
             :task/context (merge
                            {:parent-plan-id (:plan/id plan)
                             :parent-workflow-id (:workflow-id context)}
                            (select-keys context [:llm-backend :artifact-store]))})
          tasks)))

(defn- run-mini-workflow
  "Run a mini-workflow for a single DAG task.

   This executes:
   1. Create implementer agent
   2. Run inner loop with generate/validate/repair
   3. Return result with artifact and metrics

   Arguments:
   - task-def: DAG task definition with :task/description, :task/context
   - context: Execution context with :llm-backend

   Returns: {:success? bool :artifact map :metrics map :error string}"
  [task-def context]
  (let [llm-backend (:llm-backend context)
        task-context (:task/context task-def {})
        description (:task/description task-def "Implement task")

        ;; Create implementer agent
        impl-agent (agent/create-implementer {:llm-backend llm-backend})

        ;; Create task for inner loop
        inner-task (task/create-task
                    {:task/id (random-uuid)
                     :task/type :implement
                     :task/title description
                     :task/description description
                     :task/status :pending
                     :task/metadata {:dag-task-id (:task/id task-def)
                                     :parent-plan-id (:parent-plan-id task-context)}})

        ;; Create generate function using agent
        generate-fn (fn [t _ctx]
                      (let [result (agent/invoke impl-agent t context)]
                        {:artifact (:artifact result)
                         :tokens (or (get-in result [:metrics :tokens]) 0)}))

        ;; Run inner loop with minimal gates (syntax only for speed)
        loop-context (merge context
                            {:max-iterations 3
                             :repair-fn (fn [old-artifact errors _ctx]
                                          (let [result (agent/repair impl-agent old-artifact errors context)]
                                            {:success? (:success result)
                                             :artifact (:repaired result old-artifact)
                                             :tokens-used (or (get-in result [:metrics :tokens]) 0)}))})

        loop-result (loop/run-simple inner-task generate-fn loop-context)]

    (if (:success loop-result)
      {:success? true
       :artifact (:artifact loop-result)
       :metrics (:metrics loop-result {:tokens 0 :cost-usd 0.0 :duration-ms 0})}
      {:success? false
       :error (or (:error loop-result)
                  (get-in loop-result [:termination :message])
                  "Inner loop failed")
       :metrics (:metrics loop-result {:tokens 0 :cost-usd 0.0 :duration-ms 0})})))

(defn create-task-executor-fn
  "Create the execute-task-fn for the DAG scheduler.

   This function is called for each task and runs a mini-workflow:
   implement → verify (→ PR → merge in full mode)

   Arguments:
   - context: Execution context with :llm-backend, :artifact-store
   - opts: Options
     - :on-task-start - Callback (fn [task-id])
     - :on-task-complete - Callback (fn [task-id result])

   Returns: (fn [task-id dag-context] -> result)"
  [context opts]
  (let [{:keys [on-task-start on-task-complete]} opts
        llm-backend (:llm-backend context)]

    (fn [task-id dag-context]
      (when on-task-start
        (on-task-start task-id))

      (let [task-def (get-in dag-context [:run-state :run/tasks task-id])
            description (:task/description task-def "Implement task")

            result (try
                     (if llm-backend
                       ;; Real execution with LLM
                       (let [wf-result (run-mini-workflow task-def context)]
                         (if (:success? wf-result)
                           (dag/ok {:task-id task-id
                                    :description description
                                    :status :implemented
                                    :artifacts [(:artifact wf-result)]
                                    :metrics (:metrics wf-result)})
                           (dag/err :task-execution-failed
                                    (:error wf-result)
                                    {:task-id task-id
                                     :metrics (:metrics wf-result)})))

                       ;; No LLM backend - placeholder for testing DAG flow
                       (dag/ok {:task-id task-id
                                :description description
                                :status :implemented
                                :artifacts []
                                :metrics {:tokens 0 :cost-usd 0.0}}))
                     (catch Exception e
                       (dag/err :task-execution-failed
                                (str "Task failed: " (.getMessage e))
                                {:task-id task-id})))]

        (when on-task-complete
          (on-task-complete task-id result))

        result))))

;------------------------------------------------------------------------------ Layer 2
;; DAG execution

(defn execute-plan-as-dag
  "Execute a plan using the DAG executor for parallel task execution.

   Arguments:
   - plan: Plan map from planner
   - context: Execution context
     - :llm-backend - LLM client (required)
     - :artifact-store - Artifact store (optional)
     - :logger - Logger instance (optional)
     - :max-parallel - Max parallel tasks (default 4)
     - :on-task-start - Callback for task start
     - :on-task-complete - Callback for task completion

   Returns:
   {:success? boolean
    :tasks-completed int
    :tasks-failed int
    :tasks-skipped int
    :artifacts []
    :metrics {}
    :dag-result <full DAG result>}"
  [plan context]
  (let [logger (or (:logger context) (log/create-logger {:min-level :info}))
        dag-id (random-uuid)
        task-defs (plan->dag-tasks plan context)

        _ (log/info logger :dag-orchestrator :dag/starting
                    {:data {:plan-id (:plan/id plan)
                            :task-count (count task-defs)
                            :dag-id dag-id}})

        ;; Create DAG run state
        run-state (dag/create-dag-from-tasks
                   dag-id
                   task-defs
                   :budget (:budget context))

        ;; Create run atom for scheduler
        run-atom (dag/create-run-atom run-state)

        ;; Create executor function
        execute-fn (create-task-executor-fn context
                                            {:on-task-start (:on-task-start context)
                                             :on-task-complete (:on-task-complete context)})

        ;; Create scheduler context
        scheduler-context (dag/create-scheduler-context
                           :logger logger
                           :max-parallel (or (:max-parallel context) 4)
                           :execute-task-fn execute-fn)

        ;; Run the scheduler
        final-state (dag/run-scheduler run-atom scheduler-context
                                       :poll-interval-ms 100)

        ;; Aggregate results
        tasks (:run/tasks final-state)
        completed (count (filter #(= :merged (:task/status %)) (vals tasks)))
        failed (count (filter #(= :failed (:task/status %)) (vals tasks)))
        skipped (count (filter #(= :skipped (:task/status %)) (vals tasks)))]

    (log/info logger :dag-orchestrator :dag/completed
              {:data {:dag-id dag-id
                      :completed completed
                      :failed failed
                      :skipped skipped
                      :run-status (:run/status final-state)}})

    {:success? (and (zero? failed) (= completed (count tasks)))
     :tasks-completed completed
     :tasks-failed failed
     :tasks-skipped skipped
     :artifacts [] ;; TODO: Collect from task results
     :metrics (:run/metrics final-state {})
     :dag-result final-state}))

;------------------------------------------------------------------------------ Layer 3
;; Integration with workflow

(defn maybe-parallelize-plan
  "Check if plan should be parallelized and execute accordingly.

   This is the main entry point for workflow integration. After the plan phase,
   call this function to either:
   - Execute via DAG if parallelizable (returns aggregated result)
   - Return nil if plan should be executed sequentially

   Arguments:
   - plan: Plan from planner phase
   - context: Execution context

   Returns:
   - DAG execution result if parallelized
   - nil if plan should be executed sequentially"
  [plan context]
  (let [estimate (estimate-parallel-speedup plan)]
    (when (:parallelizable? estimate)
      (let [logger (or (:logger context) (log/create-logger {:min-level :info}))]
        (log/info logger :dag-orchestrator :plan/parallelizing
                  {:data {:plan-id (:plan/id plan)
                          :task-count (:task-count estimate)
                          :max-parallel (:max-parallel estimate)
                          :estimated-speedup (:estimated-speedup estimate)}}))
      (execute-plan-as-dag plan context))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test plan analysis
  (def sample-plan
    {:plan/id (random-uuid)
     :plan/name "test-plan"
     :plan/tasks
     (let [a (random-uuid)
           b (random-uuid)
           c (random-uuid)]
       [{:task/id a
         :task/description "Task A"
         :task/type :implement
         :task/dependencies []}
        {:task/id b
         :task/description "Task B"
         :task/type :implement
         :task/dependencies []}
        {:task/id c
         :task/description "Task C"
         :task/type :test
         :task/dependencies [a b]}])})

  (parallelizable-plan? sample-plan)
  ;; => true (A and B can run in parallel)

  (estimate-parallel-speedup sample-plan)
  ;; => {:parallelizable? true, :task-count 3, :max-parallel 2, :levels 2, :estimated-speedup 1.5}

  ;; Single task plan
  (parallelizable-plan?
   {:plan/tasks [{:task/id (random-uuid) :task/description "Only task"}]})
  ;; => nil (not parallelizable)

  ;; Execute plan as DAG
  (execute-plan-as-dag sample-plan {:logger (log/create-logger {:min-level :debug})})

  :leave-this-here)
