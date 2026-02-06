(ns ai.miniforge.dag-executor.scheduler
  "DAG scheduling loop for task execution.

   Layer 0: Scheduling primitives
   Layer 1: Task dispatch
   Layer 2: Main scheduling loop"
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.state :as state]
   [ai.miniforge.dag-executor.parallel :as parallel]
   [ai.miniforge.logging.interface :as log]
   [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0
;; Budget checking

(defn budget-exhausted?
  "Check if run budget constraints have been exceeded."
  [run-state]
  (let [budget (get-in run-state [:run/config :budget])
        metrics (:run/metrics run-state)]
    (when budget
      (or (and (:max-tokens budget)
               (>= (:total-tokens metrics 0) (:max-tokens budget)))
          (and (:max-cost-usd budget)
               (>= (:total-cost-usd metrics 0.0) (:max-cost-usd budget)))
          (and (:max-duration-ms budget)
               (>= (:total-duration-ms metrics 0) (:max-duration-ms budget)))))))

(defn should-checkpoint?
  "Check if we should create a checkpoint (e.g., after task completion)."
  [_run-state last-checkpoint-time checkpoint-interval-ms]
  (let [now (System/currentTimeMillis)
        elapsed (- now (or last-checkpoint-time 0))]
    (>= elapsed checkpoint-interval-ms)))

;------------------------------------------------------------------------------ Layer 0
;; Task readiness and dependency skipping

(defn compute-ready-tasks
  "Compute tasks that are ready to execute.
   A task is ready when:
   - Status is :pending
   - All dependencies are in :run/merged"
  [run-state]
  (state/ready-tasks run-state))

(defn skip-dependent-tasks
  "Skip all tasks that depend on a failed task.
   Returns updated run state."
  [run-state failed-task-id logger]
  (let [tasks (:run/tasks run-state)
        ;; Find all tasks that depend on the failed task (transitively)
        dependents (loop [to-check #{failed-task-id}
                          found #{}]
                     (if (empty? to-check)
                       found
                       (let [current (first to-check)
                             direct-deps (->> tasks
                                              (filter (fn [[_id t]]
                                                        (contains? (:task/deps t) current)))
                                              (map first)
                                              set)
                             new-deps (set/difference direct-deps found)]
                         (recur (into (disj to-check current) new-deps)
                                (into found new-deps)))))]
    (when (and logger (seq dependents))
      (log/info logger :dag-executor :tasks/skipping
                {:message "Skipping dependent tasks due to failure"
                 :data {:failed-task-id failed-task-id
                        :skipped-count (count dependents)}}))
    (reduce (fn [state task-id]
              (let [task-state (get-in state [:run/tasks task-id])]
                (if (= :pending (:task/status task-state))
                  (-> state
                      (state/update-run-task task-id
                                             #(assoc % :task/status :skipped
                                                     :task/skip-reason {:due-to-failure failed-task-id}))
                      (state/mark-task-skipped task-id))
                  state)))
            run-state
            dependents)))

;------------------------------------------------------------------------------ Layer 1
;; Task dispatch events

(defn create-dispatch-event
  "Create a dispatch event for task execution."
  [task-id action & {:keys [pr-event error]}]
  {:event/id (random-uuid)
   :event/type :task-dispatch
   :event/action action  ; :start :pr-ready :ci-result :review-result :merged :failed
   :event/task-id task-id
   :event/pr-event pr-event
   :event/error error
   :event/timestamp (java.util.Date.)})

(defn dispatch-task-start
  "Dispatch a task to start implementation.
   Returns updated run state with task in :implementing status."
  [run-atom task-id context]
  (let [logger (:logger context)
        transition-result (state/transition-task! run-atom task-id :ready logger)]
    (if (result/ok? transition-result)
      (let [next-result (state/transition-task! run-atom task-id :implementing logger)]
        (if (result/ok? next-result)
          (do
            (when logger
              (log/info logger :dag-executor :task/dispatched
                        {:message "Task dispatched for implementation"
                         :data {:task-id task-id}}))
            (result/ok (create-dispatch-event task-id :start)))
          next-result))
      transition-result)))

;------------------------------------------------------------------------------ Layer 1
;; Event handling

(defn handle-task-event
  "Handle an event from the PR lifecycle controller.
   Updates task state based on the event type."
  [run-atom event context]
  (let [logger (:logger context)
        task-id (:event/task-id event)
        action (:event/action event)]
    (when logger
      (log/debug logger :dag-executor :event/received
                 {:message "Handling task event"
                  :data {:task-id task-id :action action}}))
    (case action
      :pr-opened
      (state/transition-task! run-atom task-id :ci-running logger)

      :ci-passed
      (state/transition-task! run-atom task-id :review-pending logger)

      :ci-failed
      (let [current-state (get-in @run-atom [:run/tasks task-id])]
        (if (state/max-ci-retries-exceeded? current-state)
          (state/mark-failed! run-atom task-id
                              {:reason :ci-failed-max-retries
                               :details (:event/error event)}
                              logger)
          (do
            (state/update-task! run-atom task-id state/increment-ci-retries logger)
            (state/transition-task! run-atom task-id :responding logger))))

      :review-approved
      (state/transition-task! run-atom task-id :ready-to-merge logger)

      :review-changes-requested
      (let [current-state (get-in @run-atom [:run/tasks task-id])]
        (if (state/max-fix-iterations-exceeded? current-state)
          (state/mark-failed! run-atom task-id
                              {:reason :review-failed-max-iterations
                               :details (:event/error event)}
                              logger)
          (do
            (state/update-task! run-atom task-id state/increment-fix-iterations logger)
            (state/transition-task! run-atom task-id :responding logger))))

      :fix-pushed
      (state/transition-task! run-atom task-id :ci-running logger)

      :merge-ready
      (state/transition-task! run-atom task-id :merging logger)

      :merged
      (state/mark-merged! run-atom task-id logger)

      :merge-failed
      (state/mark-failed! run-atom task-id
                          {:reason :merge-failed
                           :details (:event/error event)}
                          logger)

      ;; Default: log unknown event
      (do
        (when logger
          (log/warn logger :dag-executor :event/unknown
                    {:message "Unknown event action"
                     :data {:action action :event event}}))
        (result/err :unknown-event
                    (str "Unknown event action: " action)
                    {:event event})))))

;------------------------------------------------------------------------------ Layer 2
;; Scheduling loop

(defn create-scheduler-context
  "Create context for the scheduling loop."
  [& {:keys [logger lock-pool max-parallel checkpoint-interval-ms
             execute-task-fn handle-pr-event-fn]
      :or {max-parallel 4
           checkpoint-interval-ms 60000}}]
  {:logger logger
   :lock-pool (or lock-pool (parallel/create-lock-pool))
   :max-parallel max-parallel
   :checkpoint-interval-ms checkpoint-interval-ms
   :execute-task-fn execute-task-fn
   :handle-pr-event-fn handle-pr-event-fn
   :last-checkpoint-time (atom (System/currentTimeMillis))})

(defn schedule-iteration
  "Perform one iteration of the scheduling loop.
   - Find ready tasks
   - Dispatch tasks respecting parallelism limits
   - Process pending events
   - Check for completion/failure

   Returns {:continue? bool :run-state state :events [...]}"
  [run-atom context]
  (let [logger (:logger context)
        lock-pool (:lock-pool context)
        max-parallel (:max-parallel context)
        run-state @run-atom]

    ;; Check termination conditions
    (cond
      ;; Budget exhausted
      (budget-exhausted? run-state)
      (do
        (when logger
          (log/warn logger :dag-executor :run/budget-exhausted
                    {:message "Run budget exhausted"
                     :data (:run/metrics run-state)}))
        {:continue? false
         :run-state (state/transition-run run-state :paused)
         :termination :budget-exhausted})

      ;; All tasks terminal
      (state/all-terminal? run-state)
      (let [final-status (state/compute-run-status run-state)]
        (when logger
          (log/info logger :dag-executor :run/completed
                    {:message "All tasks reached terminal state"
                     :data {:status final-status
                            :merged (count (:run/merged run-state))
                            :failed (count (:run/failed run-state))
                            :skipped (count (:run/skipped run-state))}}))
        {:continue? false
         :run-state (state/transition-run run-state final-status)
         :termination :all-complete})

      ;; Continue execution
      :else
      (let [ready-task-ids (compute-ready-tasks run-state)
            running-task-ids (state/running-tasks run-state)
            available-slots (- max-parallel (count running-task-ids))

            ;; Select tasks that can run in parallel
            tasks-to-dispatch (when (pos? available-slots)
                                (parallel/select-parallel-batch
                                 ready-task-ids run-state lock-pool available-slots))

            ;; Dispatch selected tasks
            dispatch-results (doall
                              (for [task-id tasks-to-dispatch]
                                (let [result (dispatch-task-start run-atom task-id context)]
                                  (when (and (result/ok? result) (:execute-task-fn context))
                                    ((:execute-task-fn context) task-id context))
                                  result)))]

        (when (and logger (seq tasks-to-dispatch))
          (log/info logger :dag-executor :scheduler/dispatched
                    {:message "Tasks dispatched"
                     :data {:dispatched-count (count tasks-to-dispatch)
                            :running-count (+ (count running-task-ids)
                                              (count tasks-to-dispatch))
                            :ready-remaining (- (count ready-task-ids)
                                                (count tasks-to-dispatch))}}))

        {:continue? true
         :run-state @run-atom
         :dispatched tasks-to-dispatch
         :dispatch-results dispatch-results}))))

(defn run-scheduler
  "Run the scheduler loop until completion or pause.

   Arguments:
   - run-atom: Atom containing run state
   - context: Scheduler context from create-scheduler-context

   Options:
   - :poll-interval-ms - Sleep between iterations (default 1000)
   - :on-iteration - Callback (fn [iteration-result]) for each iteration
   - :on-event - Callback (fn [event]) for task events

   Returns final run state."
  [run-atom context & {:keys [poll-interval-ms on-iteration _on-event]
                       :or {poll-interval-ms 1000}}]
  (let [logger (:logger context)]
    (when logger
      (log/info logger :dag-executor :scheduler/starting
                {:message "Starting scheduler"
                 :data {:max-parallel (:max-parallel context)
                        :task-count (count (:run/tasks @run-atom))}}))

    ;; Transition to running
    (swap! run-atom state/transition-run :running)

    (loop [iteration 0]
      (let [result (schedule-iteration run-atom context)]
        ;; Call iteration callback if provided
        (when on-iteration
          (on-iteration (assoc result :iteration iteration)))

        (if (:continue? result)
          (do
            ;; Sleep between iterations
            (Thread/sleep poll-interval-ms)
            (recur (inc iteration)))

          ;; Return final state
          (do
            (when logger
              (log/info logger :dag-executor :scheduler/stopped
                        {:message "Scheduler stopped"
                         :data {:iterations iteration
                                :termination (:termination result)
                                :final-status (:run/status (:run-state result))}}))
            (:run-state result)))))))

(defn pause-scheduler
  "Signal the scheduler to pause at the next safe point."
  [run-atom logger]
  (swap! run-atom state/transition-run :paused)
  (when logger
    (log/info logger :dag-executor :scheduler/pausing
              {:message "Scheduler pause requested"}))
  @run-atom)

(defn resume-scheduler
  "Resume a paused scheduler run."
  [run-atom context & opts]
  (let [logger (:logger context)
        run-state @run-atom]
    (when (= :paused (:run/status run-state))
      (when logger
        (log/info logger :dag-executor :scheduler/resuming
                  {:message "Resuming paused scheduler"}))
      (apply run-scheduler run-atom context opts))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create run state with tasks
  (def task-a-id (random-uuid))
  (def task-b-id (random-uuid))
  (def task-c-id (random-uuid))

  (def task-a (state/create-task-state task-a-id #{}))
  (def task-b (state/create-task-state task-b-id #{task-a-id}))
  (def task-c (state/create-task-state task-c-id #{task-a-id}))

  (def run-state (state/create-run-state
                  (random-uuid)
                  {task-a-id task-a
                   task-b-id task-b
                   task-c-id task-c}))

  ;; Create run atom
  (def run-atom (state/create-run-atom run-state))

  ;; Create scheduler context
  (def ctx (create-scheduler-context
            :max-parallel 2
            :execute-task-fn (fn [task-id _ctx]
                               (println "Executing:" task-id)
                               {:success true})))

  ;; Check ready tasks
  (compute-ready-tasks @run-atom)  ; => #{task-a-id}

  ;; Run one iteration
  (def iter-result (schedule-iteration run-atom ctx))
  (:dispatched iter-result)  ; => [task-a-id]

  ;; Simulate task completion
  (state/mark-merged! run-atom task-a-id nil)
  (compute-ready-tasks @run-atom)  ; => #{task-b-id task-c-id}

  ;; Run full scheduler (would need execute-task-fn implementation)
  ;; (run-scheduler run-atom ctx :poll-interval-ms 100)

  :leave-this-here)
