(ns ai.miniforge.dag-executor.state
  "DAG run and task workflow state management.

   Layer 0: State schemas and constructors
   Layer 1: State transitions (pure functions)
   Layer 2: State queries
   Layer 3: State persistence (atom-based)"
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.logging.interface :as log]
   [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0
;; State schemas and enums

(def task-statuses
  "Valid task workflow statuses.
   A task progresses through these states on its way to :merged."
  #{:pending          ; Not yet ready (dependencies not met)
    :ready            ; Dependencies met, can be started
    :implementing     ; Inner loop generating code
    :pr-opening       ; Creating branch and PR
    :ci-running       ; Waiting for CI to complete
    :review-pending   ; Waiting for approvals
    :responding       ; Fix loop responding to CI/review feedback
    :ready-to-merge   ; All checks passed, ready to merge
    :merging          ; Merge in progress
    :merged           ; TERMINAL SUCCESS
    :failed           ; TERMINAL FAILURE
    :skipped})        ; Dependency/policy skip

(def terminal-statuses
  "Terminal statuses (no further transitions)."
  #{:merged :failed :skipped})

(def run-statuses
  "Valid DAG run statuses."
  #{:pending    ; Not started
    :running    ; In progress
    :paused     ; Temporarily halted
    :completed  ; All tasks merged
    :failed     ; Unrecoverable failure
    :partial})  ; Some tasks merged, others failed/skipped

(def valid-transitions
  "Valid state transitions for task workflows."
  {:pending       #{:ready :skipped}
   :ready         #{:implementing :skipped}
   :implementing  #{:pr-opening :failed}
   :pr-opening    #{:ci-running :failed}
   :ci-running    #{:review-pending :responding :failed}
   :review-pending #{:ready-to-merge :responding}
   :responding    #{:ci-running :failed}
   :ready-to-merge #{:merging :responding}
   :merging       #{:merged :failed}
   :merged        #{}
   :failed        #{}
   :skipped       #{}})

;------------------------------------------------------------------------------ Layer 0
;; PR info schema

(defn create-pr-info
  "Create PR information map."
  [& {:keys [id url branch base-sha head-sha]}]
  {:pr/id id
   :pr/url url
   :pr/branch branch
   :pr/base-sha base-sha
   :pr/head-sha head-sha})

;------------------------------------------------------------------------------ Layer 0
;; Attempt tracking

(defn create-attempt
  "Create an attempt record for tracking implementation/fix attempts."
  [phase & {:keys [metrics]}]
  {:attempt/id (random-uuid)
   :attempt/phase phase ; :implement or :fix
   :attempt/started-at (java.util.Date.)
   :attempt/metrics (or metrics {})})

(defn complete-attempt
  "Mark an attempt as completed with result."
  [attempt result]
  (assoc attempt
         :attempt/result result ; :ok or :err
         :attempt/completed-at (java.util.Date.)))

;------------------------------------------------------------------------------ Layer 0
;; Task Workflow State constructors

(defn create-task-state
  "Create initial task workflow state.

   Arguments:
   - task-id: UUID of the task
   - deps: Set of task IDs this task depends on

   Options:
   - :max-fix-iterations - Max fix attempts (default 5)
   - :max-ci-retries - Max CI retry attempts (default 3)"
  [task-id deps & {:keys [max-fix-iterations max-ci-retries]
                   :or {max-fix-iterations 5 max-ci-retries 3}}]
  {:task/id task-id
   :task/status :pending
   :task/deps (set deps)
   :task/pr nil
   :task/attempts []
   :task/fix-iterations 0
   :task/ci-retries 0
   :task/config {:max-fix-iterations max-fix-iterations
                 :max-ci-retries max-ci-retries}
   :task/created-at (java.util.Date.)
   :task/updated-at (java.util.Date.)})

;------------------------------------------------------------------------------ Layer 0
;; DAG Run State constructors

(defn create-run-state
  "Create initial DAG run state.

   Arguments:
   - dag-id: UUID of the DAG definition
   - tasks: Map of task-id -> TaskWorkflowState

   Options:
   - :budget - Budget constraints map with :max-tokens :max-cost-usd :max-duration-ms"
  [dag-id tasks & {:keys [budget]}]
  {:dag/id dag-id
   :run/id (random-uuid)
   :run/status :pending
   :run/tasks tasks
   :run/merged #{}
   :run/failed #{}
   :run/skipped #{}
   :run/metrics {:total-tokens 0
                 :total-cost-usd 0.0
                 :total-duration-ms 0}
   :run/config (cond-> {}
                 budget (assoc :budget budget))
   :run/checkpoint nil
   :run/created-at (java.util.Date.)
   :run/updated-at (java.util.Date.)})

;------------------------------------------------------------------------------ Layer 1
;; State transition functions (pure)

(defn valid-transition?
  "Check if a state transition is valid."
  [from-status to-status]
  (contains? (get valid-transitions from-status #{}) to-status))

(defn terminal?
  "Check if a status is terminal (no further transitions)."
  [status]
  (contains? terminal-statuses status))

(defn transition-task
  "Transition a task to a new status.
   Returns updated task state or error result."
  [task-state new-status]
  (let [current-status (:task/status task-state)]
    (if (valid-transition? current-status new-status)
      (result/ok (-> task-state
                     (assoc :task/status new-status)
                     (assoc :task/updated-at (java.util.Date.))))
      (result/err :invalid-transition
                  (str "Cannot transition from " current-status " to " new-status)
                  {:from current-status
                   :to new-status
                   :valid-targets (get valid-transitions current-status)}))))

(defn set-task-pr
  "Set PR information for a task."
  [task-state pr-info]
  (-> task-state
      (assoc :task/pr pr-info)
      (assoc :task/updated-at (java.util.Date.))))

(defn add-task-attempt
  "Add an attempt record to a task."
  [task-state attempt]
  (-> task-state
      (update :task/attempts conj attempt)
      (assoc :task/updated-at (java.util.Date.))))

(defn increment-fix-iterations
  "Increment fix iteration count."
  [task-state]
  (-> task-state
      (update :task/fix-iterations inc)
      (assoc :task/updated-at (java.util.Date.))))

(defn increment-ci-retries
  "Increment CI retry count."
  [task-state]
  (-> task-state
      (update :task/ci-retries inc)
      (assoc :task/updated-at (java.util.Date.))))

(defn max-fix-iterations-exceeded?
  "Check if max fix iterations have been exceeded."
  [task-state]
  (let [max-iter (get-in task-state [:task/config :max-fix-iterations] 5)
        current (:task/fix-iterations task-state 0)]
    (>= current max-iter)))

(defn max-ci-retries-exceeded?
  "Check if max CI retries have been exceeded."
  [task-state]
  (let [max-retries (get-in task-state [:task/config :max-ci-retries] 3)
        current (:task/ci-retries task-state 0)]
    (>= current max-retries)))

;------------------------------------------------------------------------------ Layer 1
;; Run state transitions (pure)

(defn transition-run
  "Transition a run to a new status."
  [run-state new-status]
  (-> run-state
      (assoc :run/status new-status)
      (assoc :run/updated-at (java.util.Date.))))

(defn update-run-task
  "Update a task within the run state."
  [run-state task-id update-fn]
  (-> run-state
      (update-in [:run/tasks task-id] update-fn)
      (assoc :run/updated-at (java.util.Date.))))

(defn mark-task-merged
  "Mark a task as merged in the run state."
  [run-state task-id]
  (-> run-state
      (update :run/merged conj task-id)
      (assoc :run/updated-at (java.util.Date.))))

(defn mark-task-failed
  "Mark a task as failed in the run state."
  [run-state task-id]
  (-> run-state
      (update :run/failed conj task-id)
      (assoc :run/updated-at (java.util.Date.))))

(defn mark-task-skipped
  "Mark a task as skipped in the run state."
  [run-state task-id]
  (-> run-state
      (update :run/skipped conj task-id)
      (assoc :run/updated-at (java.util.Date.))))

(defn update-run-metrics
  "Update run metrics with new values."
  [run-state metric-updates]
  (-> run-state
      (update :run/metrics
              (fn [metrics]
                (merge-with (fn [old new]
                              (if (number? old)
                                (+ old new)
                                new))
                            metrics
                            metric-updates)))
      (assoc :run/updated-at (java.util.Date.))))

(defn set-checkpoint
  "Set checkpoint reference for the run."
  [run-state checkpoint-ref]
  (-> run-state
      (assoc :run/checkpoint {:ref checkpoint-ref
                              :timestamp (java.util.Date.)})
      (assoc :run/updated-at (java.util.Date.))))

;------------------------------------------------------------------------------ Layer 2
;; State queries

(defn ready-tasks
  "Get all tasks that are ready to start (dependencies satisfied).
   Returns set of task IDs."
  [run-state]
  (let [tasks (:run/tasks run-state)
        merged (:run/merged run-state)]
    (->> tasks
         (filter (fn [[_id task]]
                   (and (= :pending (:task/status task))
                        (every? merged (:task/deps task)))))
         (map first)
         set)))

(defn running-tasks
  "Get all tasks currently in progress (not pending, not terminal).
   Returns set of task IDs."
  [run-state]
  (let [tasks (:run/tasks run-state)]
    (->> tasks
         (filter (fn [[_id task]]
                   (and (not= :pending (:task/status task))
                        (not (terminal? (:task/status task))))))
         (map first)
         set)))

(defn blocked-tasks
  "Get all tasks blocked on dependencies.
   Returns map of task-id -> set of blocking task IDs."
  [run-state]
  (let [tasks (:run/tasks run-state)
        merged (:run/merged run-state)]
    (->> tasks
         (filter (fn [[_id task]]
                   (and (= :pending (:task/status task))
                        (not (every? merged (:task/deps task))))))
         (map (fn [[id task]]
                [id (set/difference (:task/deps task) merged)]))
         (into {}))))

(defn all-terminal?
  "Check if all tasks are in terminal states."
  [run-state]
  (let [tasks (:run/tasks run-state)]
    (every? (fn [[_id task]]
              (terminal? (:task/status task)))
            tasks)))

(defn compute-run-status
  "Compute the appropriate run status based on task states."
  [run-state]
  (let [tasks (:run/tasks run-state)
        task-count (count tasks)
        merged-count (count (:run/merged run-state))
        failed-count (count (:run/failed run-state))
        skipped-count (count (:run/skipped run-state))
        terminal-count (+ merged-count failed-count skipped-count)]
    (cond
      (zero? task-count) :completed
      (= terminal-count task-count)
      (cond
        (= merged-count task-count) :completed
        (pos? merged-count) :partial
        :else :failed)
      :else :running)))

;------------------------------------------------------------------------------ Layer 3
;; Atom-based state management

(defn create-run-atom
  "Create an atom containing run state for mutable operations."
  [run-state]
  (atom run-state))

(defn update-task!
  "Atomically update a task within a run atom."
  [run-atom task-id update-fn logger]
  (let [result (swap! run-atom
                      (fn [run-state]
                        (update-run-task run-state task-id update-fn)))]
    (when logger
      (log/debug logger :dag-executor :task/updated
                 {:message "Task updated"
                  :data {:task-id task-id
                         :new-status (get-in result [:run/tasks task-id :task/status])}}))
    result))

(defn transition-task!
  "Atomically transition a task to a new status within a run atom.
   Returns result with updated run state or error."
  [run-atom task-id new-status logger]
  (let [current-state @run-atom
        task-state (get-in current-state [:run/tasks task-id])]
    (if-not task-state
      (result/err :task-not-found
                  (str "Task " task-id " not found in run")
                  {:task-id task-id})
      (let [transition-result (transition-task task-state new-status)]
        (if (result/ok? transition-result)
          (do
            (swap! run-atom update-run-task task-id (constantly (:data transition-result)))
            (when logger
              (log/info logger :dag-executor :task/transitioned
                        {:message "Task status changed"
                         :data {:task-id task-id
                                :from (:task/status task-state)
                                :to new-status}}))
            (result/ok @run-atom))
          (do
            (when logger
              (log/warn logger :dag-executor :task/transition-failed
                        {:message "Invalid task transition"
                         :data {:task-id task-id
                                :error (:error transition-result)}}))
            transition-result))))))

(defn mark-merged!
  "Atomically mark a task as merged in a run atom."
  [run-atom task-id logger]
  (let [current-state @run-atom
        task-state (get-in current-state [:run/tasks task-id])]
    (if-not task-state
      (result/err :task-not-found
                  (str "Task " task-id " not found")
                  {:task-id task-id})
      (let [transition-result (transition-task task-state :merged)]
        (if (result/ok? transition-result)
          (do
            (swap! run-atom
                   (fn [state]
                     (-> state
                         (update-run-task task-id (constantly (:data transition-result)))
                         (mark-task-merged task-id))))
            (when logger
              (log/info logger :dag-executor :task/merged
                        {:message "Task merged successfully"
                         :data {:task-id task-id}}))
            (result/ok @run-atom))
          transition-result)))))

(defn mark-failed!
  "Atomically mark a task as failed in a run atom."
  [run-atom task-id error-info logger]
  (let [current-state @run-atom
        task-state (get-in current-state [:run/tasks task-id])]
    (if-not task-state
      (result/err :task-not-found
                  (str "Task " task-id " not found")
                  {:task-id task-id})
      (let [transition-result (transition-task task-state :failed)]
        (if (result/ok? transition-result)
          (do
            (swap! run-atom
                   (fn [state]
                     (-> state
                         (update-run-task task-id
                                          (fn [_t]
                                            (-> (:data transition-result)
                                                (assoc :task/error error-info))))
                         (mark-task-failed task-id))))
            (when logger
              (log/warn logger :dag-executor :task/failed
                        {:message "Task failed"
                         :data {:task-id task-id :error error-info}}))
            (result/ok @run-atom))
          transition-result)))))

(defn update-metrics!
  "Atomically update run metrics."
  [run-atom metric-updates logger]
  (swap! run-atom update-run-metrics metric-updates)
  (when logger
    (log/debug logger :dag-executor :metrics/updated
               {:message "Run metrics updated"
                :data metric-updates}))
  @run-atom)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create task workflow states
  (def task-a-id (random-uuid))
  (def task-b-id (random-uuid))

  (def task-a (create-task-state task-a-id #{}))
  (def task-b (create-task-state task-b-id #{task-a-id}))

  ;; Check initial state
  (:task/status task-a)  ; => :pending

  ;; Transition task
  (def result (transition-task task-a :ready))
  (result/ok? result)    ; => true
  (:task/status (:data result))  ; => :ready

  ;; Invalid transition
  (def bad-result (transition-task task-a :merged))
  (result/err? bad-result)  ; => true

  ;; Create run state
  (def run-state (create-run-state
                  (random-uuid)
                  {task-a-id task-a
                   task-b-id task-b}))

  ;; Query ready tasks
  (ready-tasks run-state)  ; => #{task-a-id}

  ;; Mark task-a as merged, then check
  (def run-state' (-> run-state
                      (update-run-task task-a-id #(assoc % :task/status :merged))
                      (mark-task-merged task-a-id)))
  (ready-tasks run-state')  ; => #{task-b-id}

  ;; Atom-based operations
  (def run-atom (create-run-atom run-state))
  (transition-task! run-atom task-a-id :ready nil)
  (:task/status (get-in @run-atom [:run/tasks task-a-id]))  ; => :ready

  :leave-this-here)
