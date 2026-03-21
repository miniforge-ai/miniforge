(ns ai.miniforge.task-executor.orchestrator
  "Provide execute-task-fn callback and manage concurrent task futures.

  This is the top-level integration layer that:
  - Creates the execute-task-fn callback for the DAG scheduler
  - Launches task execution in futures
  - Tracks futures for graceful shutdown
  - Handles task failures and cascading to dependents"
  (:require [ai.miniforge.task-executor.runner :as runner]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.logging.interface :as log]))

(defn create-run-context
  "Create shared context for a DAG run.

  Args:
    run-atom: Atom containing DAG run state
    config: Configuration map with:
      :workflow-id - Workflow identifier
      :executor - IEnvironmentExecutor instance
      :llm-backend - LLM backend for code generation
      :logger - Logger instance
      :event-stream - Event stream for observability
      :max-iterations - Max inner loop iterations
      :max-parallel - Max parallel tasks
      :github-token - GitHub API token
      :budget - Budget constraints
      :lock-pool - Optional lock pool (created if not provided)

  Returns: Run context map for task execution"
  [run-atom config]
  (let [lock-pool (or (:lock-pool config)
                      (dag/create-lock-pool
                        :max-worktrees (:max-parallel config 4)))]
    {:run-atom run-atom
     :workflow-id (:workflow-id config)
     :executor (:executor config)
     :llm-backend (:llm-backend config)
     :logger (:logger config)
     :event-stream (:event-stream config)
     :lock-pool lock-pool
     :config config}))

(defn log-event
  "Log an event if logger is available."
  [logger event-type data]
  (when logger
    (log/info logger :task-orchestrator event-type
              {:message (str "Task orchestrator: " (name event-type))
               :data data})))

(defn skip-dependent-tasks!
  "Skip all tasks that depend on a failed task."
  [run-atom task-id logger]
  (log-event logger :skipping-dependents {:failed-task-id task-id})

  ;; Get all task IDs from run-atom
  (let [run-state @run-atom
        all-tasks (:tasks run-state)
        dependents (filter (fn [[_tid task]]
                            (contains? (set (:dependencies task)) task-id))
                          all-tasks)]

    (doseq [[dependent-id _task] dependents]
      (log-event logger :skipping-task {:task-id dependent-id
                                        :reason :dependency-failed
                                        :failed-dependency task-id})
      (dag/mark-failed! run-atom dependent-id
                       (ex-info "Dependency failed"
                                {:dependency-id task-id})))))

(defn make-execute-task-fn
  "Create execute-task-fn callback for DAG scheduler.

  The returned function:
  - Looks up full task definition from run-atom
  - Launches runner/execute-task in a future
  - Tracks futures for graceful shutdown
  - Catches exceptions and marks task as failed
  - Cascades failures to dependent tasks

  Args:
    run-context: Shared run context (from create-run-context)

  Returns: Function (fn [task-id context] -> future)"
  [run-context]
  (let [{:keys [run-atom logger]} run-context
        ;; Atom to track active futures
        futures-atom (atom {})]

    (fn [task-id _scheduler-context]
      (let [;; Look up full task definition
            run-state @run-atom
            task (get-in run-state [:tasks task-id])]

        (if-not task
          (do
            (log-event logger :task-not-found {:task-id task-id})
            nil)

          ;; Launch task execution in future
          (let [task-future
                (future
                  (try
                    (log-event logger :task-future-started {:task-id task-id})

                    ;; Execute task through full lifecycle
                    (let [result (runner/execute-task task-id task run-context)]

                      (when-not (:ok? result)
                        ;; Task failed - cascade to dependents
                        (skip-dependent-tasks! run-atom task-id logger))

                      result)

                    (catch Exception e
                      (log-event logger :task-future-exception
                                 {:task-id task-id
                                  :error (ex-message e)})

                      ;; Mark task as failed and cascade
                      (dag/mark-failed! run-atom task-id e)
                      (skip-dependent-tasks! run-atom task-id logger)

                      {:ok? false
                       :error e})

                    (finally
                      ;; Remove from tracking
                      (swap! futures-atom dissoc task-id)
                      (log-event logger :task-future-completed {:task-id task-id}))))]

            ;; Track future
            (swap! futures-atom assoc task-id task-future)

            task-future))))))

(defn create-orchestrated-scheduler-context
  "Create scheduler context with execute-task-fn wired in.

  Convenience function that combines:
  - create-run-context
  - make-execute-task-fn
  - Scheduler context assembly

  Args:
    run-atom: Atom containing DAG run state
    config: Configuration map

  Returns: Scheduler context map with :execute-task-fn"
  [run-atom config]
  (let [run-context (create-run-context run-atom config)
        execute-task-fn (make-execute-task-fn run-context)]
    {:run-atom run-atom
     :execute-task-fn execute-task-fn
     :max-parallel (:max-parallel config 4)
     :config config}))

(defn execute-dag!
  "Top-level convenience function to execute a DAG with task execution integrated.

  Steps:
  1. Initialize DAG run-atom
  2. Create orchestrated scheduler context
  3. Run scheduler loop until completion
  4. Return final run state

  Args:
    dag-id: DAG identifier
    task-defs: Sequence of maps with :task/id and :task/deps
    config: Configuration map (see create-run-context)

  Returns: Final run-atom state

  Example:
    (execute-dag! \"dag-123\"
                  [{:task/id \"task-1\" :task/deps #{}}
                   {:task/id \"task-2\" :task/deps #{\"task-1\"}}]
                  {:workflow-id \"wf-123\"
                   :executor my-executor
                   :llm-backend my-backend
                   :logger my-logger
                   :max-parallel 4})"
  [dag-id task-defs config]
  (let [{:keys [logger budget]} config
        ;; Initialize DAG using dag-executor's function
        run-state (dag/create-dag-from-tasks dag-id task-defs :budget budget)
        run-atom (dag/create-run-atom run-state)

        ;; Create orchestrated context
        scheduler-context (create-orchestrated-scheduler-context run-atom config)]

    (log-event logger :dag-execution-starting {:dag-id dag-id
                                                :task-count (count task-defs)})

    ;; Run scheduler loop
    (try
      (loop [iteration 0]
        (let [result (dag/schedule-iteration run-atom scheduler-context)]

          (when (:ok? result)
            (let [state (:value result)
                  status (:status state)]

              (log-event logger :scheduler-iteration
                         {:iteration iteration
                          :status status
                          :tasks-pending (count (:pending state))
                          :tasks-running (count (:running state))
                          :tasks-completed (count (:completed state))})

              ;; Continue if not terminal
              (when-not (#{:completed :failed :budget-exceeded} status)
                (Thread/sleep 1000) ; Poll interval
                (recur (inc iteration)))))))

      (catch Exception e
        (log-event logger :dag-execution-error {:error (ex-message e)})
        (throw e)))

    ;; Return final state
    (let [final-state @run-atom]
      (log-event logger :dag-execution-completed
                 {:dag-id dag-id
                  :status (:status final-state)
                  :tasks-completed (count (filter #(= :merged (second %))
                                                 (:task-states final-state)))
                  :tasks-failed (count (filter #(= :failed (second %))
                                              (:task-states final-state)))})
      final-state)))
