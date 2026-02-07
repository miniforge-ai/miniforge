(ns ai.miniforge.task-executor.runner
  "Execute a single task through its full lifecycle.

  Orchestrates:
  1. Environment acquisition (worktree + executor)
  2. Code generation (inner loop)
  3. PR lifecycle (create → CI → review → merge)
  4. Event bridging (PR events → scheduler events)
  5. Resource cleanup (always in finally block)"
  (:require [ai.miniforge.task-executor.bridge :as bridge]
            [ai.miniforge.task-executor.generate :as generate]
            [ai.miniforge.pr-lifecycle.interface :as pr]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.logging.interface :as log]))

(defn create-task-context
  "Assemble context for a task execution.

  Args:
    task-id: Task identifier
    task: Task definition map
    run-context: Shared run context with executor, config, etc.

  Returns: Context map for task execution"
  [task-id task run-context]
  (let [{:keys [workflow-id executor config logger event-stream]} run-context
        {:keys [max-iterations llm-backend github-token]} config]
    {:task-id task-id
     :task task
     :workflow-id workflow-id
     :executor executor
     :logger logger
     :event-stream event-stream
     :max-iterations max-iterations
     :llm-backend llm-backend
     :github-token github-token
     :run-atom (:run-atom run-context)
     :config config}))

(defn- log-event
  "Log an event if logger is available."
  [logger event-type task-id data]
  (when logger
    (log/info logger :task-executor event-type
              {:message (str "Task executor: " (name event-type))
               :data (assoc data :task-id task-id)})))

(defn- acquire-resources!
  "Acquire worktree and environment for task execution.

  Returns: {:worktree-acquired? bool, :env-record map} or throws on error"
  [task-id lock-pool executor logger config]
  (log-event logger :acquiring-resources task-id {})

  ;; Acquire worktree semaphore (60 second timeout)
  (let [worktree-result (dag/acquire-worktree! lock-pool task-id 60000 logger)]
    (when (dag/err? worktree-result)
      (throw (ex-info "Failed to acquire worktree"
                      {:task-id task-id
                       :error (:error worktree-result)})))

    ;; Acquire environment from executor
    (let [env-config {:repo-url (:repo-url config)
                      :branch (:branch config "main")
                      :env (:env config {})
                      :resources (:resources config {})}
          env-result (dag/acquire-environment! executor task-id env-config)]
      (when (dag/err? env-result)
        (dag/release-worktree! lock-pool task-id logger)
        (throw (ex-info "Failed to acquire environment"
                        {:task-id task-id
                         :error (:error env-result)})))

      (log-event logger :resources-acquired task-id
                 {:env-id (get-in env-result [:value :env-id])})

      {:worktree-acquired? true
       :env-record (:value env-result)})))

(defn- generate-code!
  "Run inner loop to generate code artifact.

  Returns: {:artifact map, :tokens int}"
  [task-id task task-context]
  (let [{:keys [llm-backend logger event-stream workflow-id max-iterations
                env-record]} task-context
        gen-fn (generate/create-generate-fn llm-backend
                 :logger logger
                 :event-stream event-stream
                 :workflow-id workflow-id
                 :max-iterations max-iterations)
        context {:worktree-path (:worktree-path env-record)
                 :base-commit (:base-commit env-record)
                 :session-id (:session-id env-record)}]

    (log-event logger :generating-code task-id
               {:max-iterations max-iterations})

    (gen-fn task context)))

(defn- create-event-bridge
  "Create callback that bridges PR events to scheduler events.

  Returns: Function (fn [pr-event] -> nil) that translates and forwards events"
  [run-atom logger]
  (fn [pr-event]
    (when-let [scheduler-event (bridge/translate-event pr-event)]
      (log-event logger :event-bridged nil
                 {:pr-event (:event/type pr-event)
                  :scheduler-action (:event/action scheduler-event)})
      (dag/handle-task-event run-atom scheduler-event))))

(defn- run-pr-lifecycle!
  "Create PR controller and run full lifecycle.

  Returns: Final status map from run-lifecycle!"
  [task-id task code-artifact task-context]
  (let [{:keys [workflow-id logger event-stream env-record run-atom
                llm-backend max-iterations]} task-context
        {:keys [worktree-path]} env-record

        ;; Transition to :pr-opening before creating controller
        _ (dag/transition-task! run-atom task-id :pr-opening)

        ;; Create event bus and controller
        event-bus (pr/create-event-bus)
        gen-fn (generate/create-generate-fn llm-backend
                 :logger logger
                 :event-stream event-stream
                 :workflow-id workflow-id
                 :max-iterations max-iterations)

        controller (pr/create-controller
                     workflow-id      ; dag-id
                     (str (random-uuid)) ; run-id
                     task-id
                     task
                     :worktree-path worktree-path
                     :event-bus event-bus
                     :logger logger
                     :generate-fn gen-fn)

        ;; Set up event bridge
        bridge-fn (create-event-bridge run-atom logger)]

    (log-event logger :pr-lifecycle-starting task-id
               {:branch-name (str "task-" task-id)})

    ;; Subscribe to events and forward to scheduler
    (pr/subscribe! event-bus bridge-fn)

    ;; Run blocking lifecycle
    (pr/run-lifecycle! controller code-artifact)))

(defn- cleanup-resources!
  "Clean up environment and locks in finally block."
  [task-id lock-pool executor env-record logger]
  (when env-record
    (log-event logger :releasing-resources task-id
               {:env-id (:env-id env-record)})

    ;; Release environment
    (try
      (dag/release-environment! executor (:env-id env-record))
      (catch Exception e
        (log-event logger :release-environment-error task-id
                   {:error (ex-message e)})))

    ;; Release worktree and all locks
    (try
      (dag/release-all-locks! lock-pool task-id logger)
      (catch Exception e
        (log-event logger :release-locks-error task-id
                   {:error (ex-message e)})))))

(defn- update-task-metrics!
  "Update task metrics in run-atom."
  [run-atom task-id lifecycle-result]
  (let [metrics {:tokens-used (or (:total-tokens lifecycle-result) 0)
                 :pr-url (:pr-url lifecycle-result)
                 :status (:status lifecycle-result)
                 :iterations (:fix-iterations lifecycle-result 0)}]
    (dag/update-metrics! run-atom task-id metrics)))

(defn execute-task
  "Execute a single task through its full lifecycle.

  Steps:
  1. Acquire resources (worktree + environment)
  2. Generate code artifact (inner loop)
  3. Create PR lifecycle controller
  4. Run blocking PR lifecycle (open → CI → review → merge)
  5. Update metrics
  6. Clean up resources (in finally)

  Args:
    task-id: Task identifier string
    task: Task definition map
    run-context: Shared context with executor, config, logger, etc.

  Returns: Result map with :ok? and :value or :error

  Throws: None - catches all exceptions and marks task as failed"
  [task-id task run-context]
  (let [{:keys [run-atom executor logger lock-pool config]} run-context
        task-context (create-task-context task-id task run-context)]

    (log-event logger :task-execution-starting task-id
               {:description (:description task)})

    (let [env-record (atom nil)]
      (try
        ;; Step 1: Acquire resources
        (let [resources (acquire-resources! task-id lock-pool executor logger config)]
          (reset! env-record (:env-record resources)))

        ;; Step 2: Generate code
        (let [generation-result (generate-code! task-id task
                                               (assoc task-context
                                                      :env-record @env-record))
              code-artifact (:artifact generation-result)

              ;; Step 3 & 4: Run PR lifecycle (blocking)
              lifecycle-result (run-pr-lifecycle! task-id task code-artifact
                                                 (assoc task-context
                                                        :env-record @env-record))]

          ;; Step 5: Update metrics
          (update-task-metrics! run-atom task-id lifecycle-result)

          (log-event logger :task-execution-completed task-id
                     {:status (:status lifecycle-result)
                      :pr-url (:pr-url lifecycle-result)})

          {:ok? true
           :value lifecycle-result})

        (catch Exception e
          (log-event logger :task-execution-failed task-id
                     {:error (ex-message e)
                      :cause (ex-cause e)})

          ;; Mark task as failed in DAG
          (dag/mark-failed! run-atom task-id e logger)

          {:ok? false
           :error e})

        (finally
          ;; Always clean up resources
          (cleanup-resources! task-id lock-pool executor @env-record logger))))))
