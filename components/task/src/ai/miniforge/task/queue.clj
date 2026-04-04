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

(ns ai.miniforge.task.queue
  "Priority queue implementation for task scheduling.
   Layer 0: Pure priority calculation functions
   Layer 1: Queue data structure operations

   Priority is calculated based on:
   1. Workflow priority (0-10, higher = more urgent)
   2. Task age (older tasks get higher priority)
   3. Dependencies satisfied (ready tasks first)")

;------------------------------------------------------------------------------ Layer 0
;; Priority calculation (pure functions)

(def priority-weights
  "Weights for priority calculation components."
  {:workflow-priority 100  ; Base weight for workflow priority (0-10)
   :age-factor 0.001       ; Priority points per millisecond of age
   :ready-bonus 500})      ; Bonus for tasks with no unsatisfied dependencies

(defn task-age-ms
  "Calculate the age of a task in milliseconds.
   Uses :task/created-at if present, otherwise returns 0."
  [task now]
  (if-let [created-at (:task/created-at task)]
    (- now (.getTime created-at))
    0))

(defn calculate-priority
  "Calculate the priority score for a task.
   Higher score = higher priority.

   Arguments:
   - task: The task map
   - now: Current time in milliseconds (System/currentTimeMillis)
   - ready?: Boolean indicating if task dependencies are satisfied
   - wf-priority: Priority of the parent workflow (0-10, default 5)"
  [task now ready? wf-priority]
  (let [{:keys [workflow-priority age-factor ready-bonus]} priority-weights
        wf-pri (or wf-priority 5)
        age (task-age-ms task now)]
    (+ (* wf-pri workflow-priority)
       (* age age-factor)
       (if ready? ready-bonus 0))))

(defn compare-priority
  "Compare two tasks by priority (for sorting).
   Returns negative if a should come before b (higher priority)."
  [a b]
  (compare (:priority b) (:priority a)))

;------------------------------------------------------------------------------ Layer 1
;; Queue data structure

(defn create-queue
  "Create a new priority queue.
   The queue is represented as an atom containing a sorted set of task entries."
  []
  (atom {:tasks {}           ; task-id -> task map
         :priorities {}}))   ; task-id -> priority score

(defn recalculate-priorities
  "Recalculate priorities for all tasks in the queue."
  [queue-state ready-fn workflow-priority-fn]
  (let [now (System/currentTimeMillis)]
    (reduce-kv
     (fn [state task-id task]
       (let [ready? (ready-fn task-id)
             wf-priority (workflow-priority-fn task)
             priority (calculate-priority task now ready? wf-priority)]
         (assoc-in state [:priorities task-id] priority)))
     queue-state
     (:tasks queue-state))))

(defn enqueue
  "Add a task to the queue.

   Arguments:
   - queue: The queue atom
   - task: The task to add
   - opts: Map with optional :ready? and :workflow-priority keys"
  ([queue task] (enqueue queue task {}))
  ([queue task {:keys [ready? workflow-priority] :or {ready? true workflow-priority 5}}]
   (let [task-id (:task/id task)
         now (System/currentTimeMillis)
         priority (calculate-priority task now ready? workflow-priority)]
     (swap! queue (fn [state]
                    (-> state
                        (assoc-in [:tasks task-id] task)
                        (assoc-in [:priorities task-id] priority))))
     task)))

(defn dequeue
  "Remove and return the highest priority task that matches the given role.
   Returns nil if no matching task is available.

   Arguments:
   - queue: The queue atom
   - role: Optional agent role to filter by (matches against :task/type)"
  ([queue] (dequeue queue nil))
  ([queue role]
   (let [state @queue
         tasks (:tasks state)
         priorities (:priorities state)
         ;; Role-to-task-type mapping
         role-types {:planner #{:plan}
                     :architect #{:design}
                     :implementer #{:implement}
                     :tester #{:test}
                     :reviewer #{:review}
                     :sre #{:deploy}
                     :release #{:deploy}}
         valid-types (get role-types role)
         ;; Find highest priority matching task
         candidates (->> tasks
                         (filter (fn [[_ task]]
                                   (or (nil? valid-types)
                                       (contains? valid-types (:task/type task)))))
                         (map (fn [[id task]]
                                {:task-id id
                                 :task task
                                 :priority (get priorities id 0)}))
                         (sort-by :priority >))]
     (when-let [{:keys [task-id task]} (first candidates)]
       (swap! queue (fn [state]
                      (-> state
                          (update :tasks dissoc task-id)
                          (update :priorities dissoc task-id))))
       task))))

(defn peek-queue
  "Return the highest priority task without removing it.
   Returns nil if queue is empty."
  ([queue] (peek-queue queue nil))
  ([queue role]
   (let [state @queue
         tasks (:tasks state)
         priorities (:priorities state)
         role-types {:planner #{:plan}
                     :architect #{:design}
                     :implementer #{:implement}
                     :tester #{:test}
                     :reviewer #{:review}
                     :sre #{:deploy}
                     :release #{:deploy}}
         valid-types (get role-types role)
         candidates (->> tasks
                         (filter (fn [[_ task]]
                                   (or (nil? valid-types)
                                       (contains? valid-types (:task/type task)))))
                         (map (fn [[id task]]
                                {:task-id id
                                 :task task
                                 :priority (get priorities id 0)}))
                         (sort-by :priority >))]
     (:task (first candidates)))))

(defn queue-size
  "Return the number of tasks in the queue."
  [queue]
  (count (:tasks @queue)))

(defn queue-empty?
  "Return true if the queue is empty."
  [queue]
  (zero? (queue-size queue)))

(defn remove-from-queue
  "Remove a specific task from the queue by ID.
   Returns the removed task or nil if not found."
  [queue task-id]
  (let [task (get-in @queue [:tasks task-id])]
    (when task
      (swap! queue (fn [state]
                     (-> state
                         (update :tasks dissoc task-id)
                         (update :priorities dissoc task-id))))
      task)))

(defn update-priority
  "Update the priority of a task in the queue.

   Arguments:
   - queue: The queue atom
   - task-id: ID of the task to update
   - opts: Map with :ready? and/or :workflow-priority"
  [queue task-id {:keys [ready? workflow-priority] :or {ready? true workflow-priority 5}}]
  (when-let [task (get-in @queue [:tasks task-id])]
    (let [now (System/currentTimeMillis)
          priority (calculate-priority task now ready? workflow-priority)]
      (swap! queue assoc-in [:priorities task-id] priority)
      priority)))

(defn tasks-in-queue
  "Return all tasks currently in the queue, sorted by priority (highest first)."
  [queue]
  (let [state @queue
        tasks (:tasks state)
        priorities (:priorities state)]
    (->> tasks
         (map (fn [[id task]]
                (assoc task :priority (get priorities id 0))))
         (sort-by :priority >)
         vec)))

(defn clear-queue
  "Remove all tasks from the queue."
  [queue]
  (reset! queue {:tasks {} :priorities {}}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a queue
  (def q (create-queue))

  ;; Add some tasks
  (enqueue q {:task/id (random-uuid) :task/type :implement :task/status :pending}
           {:workflow-priority 5 :ready? true})
  (enqueue q {:task/id (random-uuid) :task/type :plan :task/status :pending}
           {:workflow-priority 8 :ready? true})
  (enqueue q {:task/id (random-uuid) :task/type :test :task/status :pending}
           {:workflow-priority 5 :ready? false})

  ;; Check size
  (queue-size q)  ; => 3

  ;; Peek at highest priority
  (peek-queue q)

  ;; Dequeue for a specific role
  (dequeue q :planner)  ; Returns the plan task (priority 8)
  (dequeue q :implementer)  ; Returns the implement task

  ;; Queue operations
  (tasks-in-queue q)
  (clear-queue q)

  :leave-this-here)
