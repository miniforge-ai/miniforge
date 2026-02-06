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

(defn- compute-max-level-width
  "Compute the maximum number of tasks that can run in parallel at any level.

   Uses a level-by-level traversal of the dependency graph to find the widest level."
  [tasks]
  (let [deps-map (into {}
                       (map (fn [t]
                              [(:task/id t) (set (:task/dependencies t []))])
                            tasks))]
    (loop [remaining (set (map :task/id tasks))
           completed #{}
           max-width 0]
      (if (empty? remaining)
        max-width
        (let [ready (filter (fn [id]
                              (every? completed (get deps-map id #{})))
                            remaining)
              width (count ready)]
          (recur (apply disj remaining ready)
                 (into completed ready)
                 (max max-width width)))))))

(defn parallelizable-plan?
  "Check if a plan should be executed via DAG rather than sequentially.

   A plan is parallelizable when at any level of the dependency graph,
   more than one task can run concurrently.

   Arguments:
   - plan: Plan map from planner with :plan/tasks

   Returns: boolean"
  [plan]
  (let [tasks (:plan/tasks plan [])]
    (when (> (count tasks) 1)
      (> (compute-max-level-width tasks) 1))))

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

(defn- workflow-result->dag-result
  "Convert mini-workflow result to DAG result format."
  [task-id description wf-result]
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

(defn- placeholder-result
  "Create placeholder success result for testing DAG flow without LLM."
  [task-id description]
  (dag/ok {:task-id task-id
           :description description
           :status :implemented
           :artifacts []
           :metrics {:tokens 0 :cost-usd 0.0}}))

(defn execute-single-task
  "Execute a single DAG task, returning a DAG result.

   Arguments:
   - task-def: Task definition from DAG run state
   - context: Execution context with optional :llm-backend

   Returns: dag/ok or dag/err result"
  [task-def context]
  (let [task-id (:task/id task-def)
        description (:task/description task-def "Implement task")
        llm-backend (:llm-backend context)]
    (try
      (if llm-backend
        (workflow-result->dag-result task-id description
                                     (run-mini-workflow task-def context))
        (placeholder-result task-id description))
      (catch Exception e
        (dag/err :task-execution-failed
                 (str "Task failed: " (.getMessage e))
                 {:task-id task-id})))))

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
  (let [{:keys [on-task-start on-task-complete]} opts]
    (fn [task-id dag-context]
      (when on-task-start
        (on-task-start task-id))
      (let [task-def (get-in dag-context [:run-state :run/tasks task-id])
            result (execute-single-task task-def context)]
        (when on-task-complete
          (on-task-complete task-id result))
        result))))

;------------------------------------------------------------------------------ Layer 2
;; Synchronous DAG execution

(defn- compute-ready-tasks
  "Find tasks that are ready to execute (all deps completed)."
  [tasks-map completed-ids]
  (filter (fn [[task-id task]]
            (and (not (contains? completed-ids task-id))
                 (every? #(contains? completed-ids %) (:task/deps task #{}))))
          tasks-map))

(defn- execute-tasks-batch
  "Execute a batch of tasks in parallel, return results."
  [tasks execute-fn context]
  (let [futures (doall
                 (for [[task-id task] tasks]
                   [task-id (future (execute-fn task context))]))]
    (into {} (for [[task-id f] futures]
               [task-id @f]))))

(defn execute-plan-as-dag
  "Execute a plan using parallel task execution.

   Arguments:
   - plan: Plan map from planner
   - context: Execution context
     - :llm-backend - LLM client (required for real execution)
     - :artifact-store - Artifact store (optional)
     - :logger - Logger instance (optional)
     - :max-parallel - Max parallel tasks (default 4)
     - :on-task-start - Callback for task start
     - :on-task-complete - Callback for task completion

   Returns:
   {:success? boolean
    :tasks-completed int
    :tasks-failed int
    :artifacts []
    :metrics {}}"
  [plan context]
  (let [logger (or (:logger context) (log/create-logger {:min-level :info}))
        max-parallel (or (:max-parallel context) 4)
        task-defs (plan->dag-tasks plan context)
        tasks-map (into {} (map (fn [t] [(:task/id t) t]) task-defs))
        on-task-start (:on-task-start context)
        on-task-complete (:on-task-complete context)]

    (log/info logger :dag-orchestrator :dag/starting
              {:data {:plan-id (:plan/id plan)
                      :task-count (count task-defs)}})

    ;; Execute tasks level by level
    (loop [completed-ids #{}
           failed-ids #{}
           all-results {}
           iteration 0]

      (let [ready-tasks (compute-ready-tasks tasks-map completed-ids)]
        (cond
          ;; All done
          (empty? ready-tasks)
          (let [completed (count completed-ids)
                failed (count failed-ids)
                artifacts (mapcat #(get-in % [:data :artifacts] []) (vals all-results))
                total-tokens (reduce + 0 (map #(get-in % [:data :metrics :tokens] 0) (vals all-results)))]

            (log/info logger :dag-orchestrator :dag/completed
                      {:data {:completed completed
                              :failed failed
                              :iterations iteration}})

            {:success? (zero? failed)
             :tasks-completed completed
             :tasks-failed failed
             :artifacts (vec artifacts)
             :metrics {:tokens total-tokens}})

          ;; Safety limit
          (> iteration 100)
          {:success? false
           :tasks-completed (count completed-ids)
           :tasks-failed (count failed-ids)
           :artifacts []
           :metrics {}
           :error "Max iterations exceeded"}

          ;; Execute ready tasks
          :else
          (let [batch (take max-parallel ready-tasks)
                _ (doseq [[task-id _] batch]
                    (when on-task-start (on-task-start task-id)))

                batch-results (execute-tasks-batch batch execute-single-task context)

                _ (doseq [[task-id result] batch-results]
                    (when on-task-complete (on-task-complete task-id result)))

                new-completed (into completed-ids
                                    (map first (filter #(dag/ok? (second %)) batch-results)))
                new-failed (into failed-ids
                                 (map first (filter #(not (dag/ok? (second %))) batch-results)))]

            (recur new-completed
                   new-failed
                   (merge all-results batch-results)
                   (inc iteration))))))))

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
