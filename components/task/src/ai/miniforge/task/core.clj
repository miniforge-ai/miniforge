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

(ns ai.miniforge.task.core
  "Task CRUD operations and state machine.
   Layer 0: State machine definitions and pure utilities
   Layer 1: Task store operations (CRUD)
   Layer 2: Orchestration (queries, decomposition, state transitions)"
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.task.lifecycle-config :as lifecycle-config]
   [ai.miniforge.task.messages :as messages]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; State machine definitions and pure utilities

(def initial-task-status
  (lifecycle-config/initial-task-status))

(def invalid-transition-message
  (messages/t :task/invalid-transition))

(def task-not-found-message
  (messages/t :task/not-found))

(def parent-task-not-found-message
  (messages/t :task/parent-not-found))

(def task-machine-definition
  (lifecycle-config/machine-definition))

(def task-machine
  (fsm/define-machine task-machine-definition))

(defn- final-state-definition?
  [[_ state-definition]]
  (= :final (:type state-definition)))

(defn- transition-targets
  [state-definition]
  (->> (get state-definition :on {})
       vals
       (map (fn transition-target [target-or-config]
              (if (keyword? target-or-config)
                target-or-config
                (:target target-or-config))))
       set))

(defn- target-state
  [target-or-config]
  (if (keyword? target-or-config)
    target-or-config
    (:target target-or-config)))

(defn- state-transition-events
  [state state-definition]
  (reduce-kv
   (fn [events event target-or-config]
     (assoc events [state (target-state target-or-config)] event))
   {}
   (get state-definition :on {})))

(defn- derive-valid-transitions
  [machine-definition]
  (reduce-kv
   (fn [transitions state state-definition]
     (assoc transitions state (transition-targets state-definition)))
   {}
   (:fsm/states machine-definition)))

(defn- derive-transition-events
  [machine-definition]
  (reduce-kv
   (fn [event-map state state-definition]
     (merge event-map (state-transition-events state state-definition)))
   {}
   (:fsm/states machine-definition)))

(def valid-transitions
  "Valid state transitions for tasks.
   Maps from-state to set of allowed to-states."
  (derive-valid-transitions task-machine-definition))

(def transition-events
  (derive-transition-events task-machine-definition))

(def terminal-task-statuses
  (into #{}
        (comp (filter final-state-definition?)
              (map first))
        (:fsm/states task-machine-definition)))

(defn- invalid-transition-data
  [from-state to-state]
  {:from from-state
   :to to-state
   :valid-targets (get valid-transitions from-state #{})})

(defn- success-task-result
  [result]
  (merge {:outcome :success} result))

(defn- failed-task-result
  [error]
  {:outcome :failure
   :error (if (string? error) error (str error))})

(defn valid-transition?
  "Check if a state transition is valid according to the state machine."
  [from-state to-state]
  (contains? (get valid-transitions from-state #{}) to-state))

(defn transition-result
  "Anomaly-returning FSM transition check.

   Returns `to-state` when the transition is allowed by the FSM, or an
   `:invalid-input` anomaly when the transition is rejected. The
   anomaly's `:anomaly/data` carries `:from`, `:to`, and
   `:valid-targets` (the set of states reachable from `from-state`).

   This is the canonical, anomaly-returning entry point. The boundary
   site `validate-transition` inlines a `response/throw-anomaly!` when
   an anomaly is observed, preserving the legacy thrown-exception
   contract for in-component callers (`transition-task!`) and external
   consumers that branch on the throw."
  [from-state to-state]
  (if (contains? transition-events [from-state to-state])
    to-state
    (anomaly/anomaly :invalid-input
                     invalid-transition-message
                     (invalid-transition-data from-state to-state))))

(defn validate-transition
  "Validate a state transition.

   Returns `to-state` on success. On FSM rejection, escalates the
   anomaly returned by `transition-result` to a slingshot throw with
   category `:anomalies/conflict` — task transition rejection is a
   programmer-error / state-mismatch boundary condition, not a runtime
   anomaly to be carried as data.

   For an anomaly-returning equivalent that callers can branch on as
   data, use `transition-result` directly."
  [from-state to-state]
  (let [result (transition-result from-state to-state)]
    (if (anomaly/anomaly? result)
      (response/throw-anomaly! :anomalies/conflict
                               (:anomaly/message result)
                               (:anomaly/data result))
      result)))

(defn make-task
  "Create a new task map with required fields.
   Generates a UUID if not provided."
  [{:keys [task/type task/status task/constraints]
    :or {status initial-task-status}
    :as task-data}]
  (let [task-id (get task-data :task/id (random-uuid))
        base-task {:task/id task-id
                   :task/type type
                   :task/status status}
        constraint-data (when constraints
                          {:task/constraints constraints})
        additional-task-data (dissoc task-data
                                     :task/id
                                     :task/type
                                     :task/status
                                     :task/constraints)
        task (merge base-task constraint-data additional-task-data)]
    (schema/validate schema/Task task)))

(defn compute-child-tasks
  "Pure function to compute child task specs from parent task ID and sub-tasks.
   Returns a vector of child task specs with parent references."
  [parent-task-id sub-tasks]
  (mapv #(assoc % :task/parent parent-task-id) sub-tasks))

;------------------------------------------------------------------------------ Layer 1
;; Task store operations

(defonce ^{:private true :doc "In-memory atom-based task storage."} task-store
  (atom {}))

(defn reset-store!
  "Reset the task store to empty. Useful for testing."
  []
  (reset! task-store {}))

(defn get-store
  "Return the current state of the task store."
  []
  @task-store)

(defn create-task!
  "Create a new task and add it to the store.
   Validates the task data against the schema.
   Returns the created task."
  ([task-data] (create-task! task-data nil))
  ([task-data logger]
   (let [task (make-task task-data)]
     (swap! task-store assoc (:task/id task) task)
     (when logger
       (log/info logger :agent :task/created
                 {:message (messages/t :task/created)
                  :data {:task-id (:task/id task)
                         :task-type (:task/type task)}}))
     task)))

(defn get-task
  "Get a task by ID, or nil if not found."
  [task-id]
  (get @task-store task-id))

(defn lookup-task
  "Anomaly-returning task lookup.

   Returns the task map when present, or a `:not-found` anomaly when
   no task exists for `task-id`. The anomaly's `:anomaly/data` carries
   `:task-id`.

   This is the canonical, anomaly-returning entry point. The boundary
   sites `update-task!`, `delete-task!`, `transition-task!`, and
   `decompose-task!` inline a `response/throw-anomaly!` when an anomaly
   is observed, preserving the legacy thrown-exception contract for
   external consumers that branch on the throw."
  ([task-id] (lookup-task task-id task-not-found-message))
  ([task-id message]
   (if-let [task (get-task task-id)]
     task
     (anomaly/anomaly :not-found message {:task-id task-id}))))

(defn- lookup-task!
  "Boundary helper. Calls `lookup-task`; on anomaly result raises a
   slingshot `:anomalies/not-found` throw. Used by mutating CRUD and
   transition functions where missing-task is a caller error rather
   than a runtime anomaly to be carried as data."
  ([task-id] (lookup-task! task-id task-not-found-message))
  ([task-id message]
   (let [result (lookup-task task-id message)]
     (if (anomaly/anomaly? result)
       (response/throw-anomaly! :anomalies/not-found
                                (:anomaly/message result)
                                (:anomaly/data result))
       result))))

(defn update-task!
  "Update a task with new data.
   Validates the resulting task against the schema.
   Returns the updated task. Raises a slingshot `:anomalies/not-found`
   throw when no task exists for `task-id` — see `lookup-task` for the
   anomaly-returning equivalent."
  ([task-id changes] (update-task! task-id changes nil))
  ([task-id changes logger]
   (let [existing (lookup-task! task-id)
         updated (merge existing changes)
         validated (schema/validate schema/Task updated)]
     (swap! task-store assoc task-id validated)
     (when logger
       (log/debug logger :agent :task/updated
                  {:message (messages/t :task/updated)
                   :data {:task-id task-id
                          :changes (keys changes)}}))
     validated)))

(defn delete-task!
  "Delete a task by ID.
   Returns the deleted task. Raises a slingshot `:anomalies/not-found`
   throw when no task exists for `task-id` — see `lookup-task` for the
   anomaly-returning equivalent."
  ([task-id] (delete-task! task-id nil))
  ([task-id logger]
   (let [task (lookup-task! task-id)]
     (swap! task-store dissoc task-id)
     (when logger
       (log/info logger :agent :task/deleted
                 {:message (messages/t :task/deleted)
                  :data {:task-id task-id}}))
     task)))

;------------------------------------------------------------------------------ Layer 2
;; Orchestration (state transitions, queries, decomposition)

;; State transitions

(defn transition-task!
  "Internal helper to perform a state transition with logging.

   Raises a slingshot `:anomalies/not-found` throw when no task exists
   for `task-id`, or `:anomalies/conflict` when the FSM rejects the
   transition. See `lookup-task` and `transition-result` for the
   anomaly-returning equivalents."
  [task-id to-state additional-changes logger event-key message]
  (let [task (lookup-task! task-id)]
    (validate-transition (:task/status task) to-state)
    (let [changes (merge {:task/status to-state} additional-changes)
          updated (update-task! task-id changes)]
      (when logger
        (log/info logger :agent event-key
                  {:message message
                   :data {:task-id task-id
                          :from-status (:task/status task)
                          :to-status to-state}}))
      updated)))

(defn start-task!
  "Transition a task from :pending to :running.
   Assigns the task to the specified agent."
  ([task-id agent-id] (start-task! task-id agent-id nil))
  ([task-id agent-id logger]
   (transition-task! task-id :running
                     {:task/agent agent-id}
                     logger :task/started (messages/t :task/started))))

(defn complete-task!
  "Transition a task from :running to :completed.
   Stores the result in :task/result."
  ([task-id result] (complete-task! task-id result nil))
  ([task-id result logger]
   (let [full-result (success-task-result result)]
     (transition-task! task-id :completed
                       {:task/result full-result}
                       logger :task/completed (messages/t :task/completed)))))

(defn fail-task!
  "Transition a task from :running to :failed.
   Stores the error information in :task/result."
  ([task-id error] (fail-task! task-id error nil))
  ([task-id error logger]
   (let [task-result (failed-task-result error)]
     (transition-task! task-id :failed
                       {:task/result task-result}
                       logger :task/failed (messages/t :task/failed)))))

(defn block-task!
  "Transition a task from :pending to :blocked.
   Records the blocking reason in metadata."
  ([task-id reason] (block-task! task-id reason nil))
  ([task-id reason logger]
   (transition-task! task-id :blocked
                     {:task/blocked-reason reason}
                     logger :task/blocked (messages/t :task/blocked))))

(defn unblock-task!
  "Transition a task from :blocked to :pending.
   Clears the blocking reason."
  ([task-id] (unblock-task! task-id nil))
  ([task-id logger]
   (let [updated (transition-task! task-id :pending
                                   {}
                                   logger :task/unblocked (messages/t :task/unblocked))]
     ;; Remove the blocked-reason key
     (update-task! (:task/id updated) (dissoc updated :task/blocked-reason))
     updated)))

;; Task queries

(defn tasks-by-status
  "Get all tasks with the specified status."
  [status]
  (->> (vals @task-store)
       (filter #(= status (:task/status %)))
       vec))

(defn tasks-by-agent
  "Get all tasks assigned to the specified agent."
  [agent-id]
  (->> (vals @task-store)
       (filter #(= agent-id (:task/agent %)))
       vec))

(defn tasks-by-type
  "Get all tasks of the specified type."
  [task-type]
  (->> (vals @task-store)
       (filter #(= task-type (:task/type %)))
       vec))

(defn all-tasks
  "Get all tasks in the store."
  []
  (vec (vals @task-store)))

;; Task decomposition

(defn decompose-task!
  "Decompose a parent task into sub-tasks.
   Creates parent/child relationships between tasks.
   Returns the updated parent task with children.

   This function separates pure computation from side effects:
   1. Validates parent exists
   2. Computes child task specs (pure)
   3. Creates child tasks (side effects)
   4. Updates parent with child references (side effects)"
  ([parent-task-id sub-tasks] (decompose-task! parent-task-id sub-tasks nil))
  ([parent-task-id sub-tasks logger]
   ;; Validate parent exists before any side effects. Raises
   ;; :anomalies/not-found when missing — see `lookup-task` for the
   ;; anomaly-returning equivalent.
   (lookup-task! parent-task-id parent-task-not-found-message)
   ;; Compute child task specs with parent references (pure)
   (let [child-specs (compute-child-tasks parent-task-id sub-tasks)
         ;; Create all child tasks (side effects)
         child-ids (mapv (fn [child-spec]
                           (:task/id (create-task! child-spec logger)))
                         child-specs)
         ;; Update parent with child references (side effect)
         updated-parent (update-task! parent-task-id
                                      {:task/children child-ids}
                                      logger)]
     (when logger
       (log/info logger :agent :task/decomposed
                 {:message (messages/t :task/decomposed)
                  :data {:parent-id parent-task-id
                         :child-count (count child-ids)
                         :child-ids child-ids}}))
     updated-parent)))

(defn get-children
  "Get all child tasks of the specified task."
  [task-id]
  (let [task (get-task task-id)]
    (when task
      (->> (:task/children task [])
           (mapv get-task)
           (filterv some?)))))

(defn get-parent
  "Get the parent task of the specified task, or nil if no parent."
  [task-id]
  (when-let [task (get-task task-id)]
    (when-let [parent-id (:task/parent task)]
      (get-task parent-id))))

(defn get-root-task
  "Get the top-level ancestor task of the specified task.
   Returns the task itself if it has no parent."
  [task-id]
  (loop [current-id task-id]
    (let [task (get-task current-id)]
      (if-let [parent-id (:task/parent task)]
        (recur parent-id)
        task))))

(defn all-children-completed?
  "Check if all children of a task are completed."
  [task-id]
  (let [children (get-children task-id)]
    (and (seq children)
         (every? #(= :completed (:task/status %)) children))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a task
  (reset-store!)
  (def task1 (create-task! {:task/type :implement
                            :task/constraints {:budget {:tokens 50000}}}))

  ;; Get it back
  (get-task (:task/id task1))

  ;; Start the task
  (def agent-id (random-uuid))
  (start-task! (:task/id task1) agent-id)

  ;; Complete it
  (complete-task! (:task/id task1) {:signals [:artifacts-created]})

  ;; Query by status
  (tasks-by-status :completed)

  ;; Decomposition
  (reset-store!)
  (def parent (create-task! {:task/type :plan}))
  (decompose-task! (:task/id parent)
                   [{:task/type :design}
                    {:task/type :implement}
                    {:task/type :test}])
  (get-children (:task/id parent))
  (get-parent (-> (get-children (:task/id parent)) first :task/id))
  (get-root-task (-> (get-children (:task/id parent)) first :task/id))

  :leave-this-here)
