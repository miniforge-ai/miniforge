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
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; State machine definitions and pure utilities

(def initial-task-status
  :pending)

(def invalid-transition-message
  "Invalid state transition")

(def task-not-found-message
  "Task not found")

(def parent-task-not-found-message
  "Parent task not found")

(def task-machine-definition
  {:fsm/id :task/lifecycle
   :fsm/initial initial-task-status
   :fsm/states
   {:pending {:on {:start :running
                   :block :blocked}}
    :running {:on {:complete :completed
                   :fail :failed}}
    :blocked {:on {:unblock :pending}}
    :completed {:type :final}
    :failed {:type :final}}})

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
     (reduce-kv
      (fn [state-events event target-or-config]
        (let [target-state (if (keyword? target-or-config)
                             target-or-config
                             (:target target-or-config))]
          (assoc state-events [state target-state] event)))
      event-map
      (get state-definition :on {})))
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

(defn validate-transition
  "Validate a state transition, throwing if invalid."
  [from-state to-state]
  (let [transition-event (get transition-events [from-state to-state])]
    (when-not transition-event
      (throw (ex-info invalid-transition-message
                      (invalid-transition-data from-state to-state)))))
  to-state)

(defn make-task
  "Create a new task map with required fields.
   Generates a UUID if not provided."
  [{:keys [task/id task/type task/status task/constraints]
    :or {status initial-task-status}
    :as task-data}]
  (let [task-id (or id (random-uuid))
        base-task {:task/id task-id
                   :task/type type
                   :task/status status}
        constraint-data (when constraints {:task/constraints constraints})
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
                 {:message "Task created"
                  :data {:task-id (:task/id task)
                         :task-type (:task/type task)}}))
     task)))

(defn get-task
  "Get a task by ID, or nil if not found."
  [task-id]
  (get @task-store task-id))

(defn update-task!
  "Update a task with new data.
   Validates the resulting task against the schema.
   Returns the updated task or throws if task not found."
  ([task-id changes] (update-task! task-id changes nil))
  ([task-id changes logger]
   (if-let [existing (get-task task-id)]
     (let [updated (merge existing changes)
           validated (schema/validate schema/Task updated)]
       (swap! task-store assoc task-id validated)
       (when logger
         (log/debug logger :agent :task/updated
                    {:message "Task updated"
                     :data {:task-id task-id
                            :changes (keys changes)}}))
       validated)
     (throw (ex-info task-not-found-message {:task-id task-id})))))

(defn delete-task!
  "Delete a task by ID.
   Returns the deleted task or throws if not found."
  ([task-id] (delete-task! task-id nil))
  ([task-id logger]
   (if-let [task (get-task task-id)]
     (do
       (swap! task-store dissoc task-id)
       (when logger
         (log/info logger :agent :task/deleted
                   {:message "Task deleted"
                    :data {:task-id task-id}}))
       task)
     (throw (ex-info task-not-found-message {:task-id task-id})))))

;------------------------------------------------------------------------------ Layer 2
;; Orchestration (state transitions, queries, decomposition)

;; State transitions

(defn transition-task!
  "Internal helper to perform a state transition with logging."
  [task-id to-state additional-changes logger event-key message]
  (let [task (get-task task-id)]
    (when-not task
      (throw (ex-info task-not-found-message {:task-id task-id})))
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
                     logger :task/started "Task started")))

(defn complete-task!
  "Transition a task from :running to :completed.
   Stores the result in :task/result."
  ([task-id result] (complete-task! task-id result nil))
  ([task-id result logger]
   (let [full-result (success-task-result result)]
     (transition-task! task-id :completed
                       {:task/result full-result}
                       logger :task/completed "Task completed"))))

(defn fail-task!
  "Transition a task from :running to :failed.
   Stores the error information in :task/result."
  ([task-id error] (fail-task! task-id error nil))
  ([task-id error logger]
   (let [task-result (failed-task-result error)]
     (transition-task! task-id :failed
                       {:task/result task-result}
                       logger :task/failed "Task failed"))))

(defn block-task!
  "Transition a task from :pending to :blocked.
   Records the blocking reason in metadata."
  ([task-id reason] (block-task! task-id reason nil))
  ([task-id reason logger]
   (transition-task! task-id :blocked
                     {:task/blocked-reason reason}
                     logger :task/blocked "Task blocked")))

(defn unblock-task!
  "Transition a task from :blocked to :pending.
   Clears the blocking reason."
  ([task-id] (unblock-task! task-id nil))
  ([task-id logger]
   (let [updated (transition-task! task-id :pending
                                   {}
                                   logger :task/unblocked "Task unblocked")]
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
   (let [parent (get-task parent-task-id)]
     (when-not parent
       (throw (ex-info parent-task-not-found-message
                       {:task-id parent-task-id})))
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
                   {:message "Task decomposed into sub-tasks"
                    :data {:parent-id parent-task-id
                           :child-count (count child-ids)
                           :child-ids child-ids}}))
       updated-parent))))

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
