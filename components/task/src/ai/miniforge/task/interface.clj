(ns ai.miniforge.task.interface
  "Public API for the task component.
   Provides task CRUD, state transitions, priority queue, and dependency graph."
  (:require
   [ai.miniforge.task.core :as core]
   [ai.miniforge.task.queue :as queue]
   [ai.miniforge.task.graph :as graph]))

;------------------------------------------------------------------------------ Layer 0
;; Task CRUD operations

(defn create-task
  "Create a new task and add it to the store.

   Arguments:
   - task-data: Map with task fields (:task/type required, :task/status defaults to :pending)
   - logger: Optional logger for structured logging

   Returns the created task.

   Example:
     (create-task {:task/type :implement
                   :task/constraints {:budget {:tokens 50000}}})"
  ([task-data] (core/create-task! task-data))
  ([task-data logger] (core/create-task! task-data logger)))

(defn get-task
  "Get a task by ID, or nil if not found."
  [task-id]
  (core/get-task task-id))

(defn update-task
  "Update a task with new data.
   Validates the resulting task against the schema.
   Returns the updated task or throws if task not found."
  ([task-id changes] (core/update-task! task-id changes))
  ([task-id changes logger] (core/update-task! task-id changes logger)))

(defn delete-task
  "Delete a task by ID.
   Returns the deleted task or throws if not found."
  ([task-id] (core/delete-task! task-id))
  ([task-id logger] (core/delete-task! task-id logger)))

;------------------------------------------------------------------------------ Layer 1
;; State transitions

(defn start-task
  "Transition a task from :pending to :running.
   Assigns the task to the specified agent.

   Arguments:
   - task-id: UUID of the task
   - agent-id: UUID of the agent to assign
   - logger: Optional logger for structured logging

   Throws if:
   - Task not found
   - Task is not in :pending state"
  ([task-id agent-id] (core/start-task! task-id agent-id))
  ([task-id agent-id logger] (core/start-task! task-id agent-id logger)))

(defn complete-task
  "Transition a task from :running to :completed.
   Stores the result in :task/result.

   Arguments:
   - task-id: UUID of the task
   - result: Map with result data (merged with {:outcome :success})
   - logger: Optional logger

   Throws if task not in :running state."
  ([task-id result] (core/complete-task! task-id result))
  ([task-id result logger] (core/complete-task! task-id result logger)))

(defn fail-task
  "Transition a task from :running to :failed.
   Stores the error information in :task/result.

   Arguments:
   - task-id: UUID of the task
   - error: Error message string or exception
   - logger: Optional logger

   Throws if task not in :running state."
  ([task-id error] (core/fail-task! task-id error))
  ([task-id error logger] (core/fail-task! task-id error logger)))

(defn block-task
  "Transition a task from :pending to :blocked.
   Records the blocking reason.

   Arguments:
   - task-id: UUID of the task
   - reason: String describing why task is blocked
   - logger: Optional logger

   Throws if task not in :pending state."
  ([task-id reason] (core/block-task! task-id reason))
  ([task-id reason logger] (core/block-task! task-id reason logger)))

(defn unblock-task
  "Transition a task from :blocked to :pending.
   Clears the blocking reason.

   Arguments:
   - task-id: UUID of the task
   - logger: Optional logger

   Throws if task not in :blocked state."
  ([task-id] (core/unblock-task! task-id))
  ([task-id logger] (core/unblock-task! task-id logger)))

;------------------------------------------------------------------------------ Layer 2
;; Task queries

(defn tasks-by-status
  "Get all tasks with the specified status.

   Arguments:
   - status: One of :pending :running :completed :failed :blocked

   Returns a vector of matching tasks."
  [status]
  (core/tasks-by-status status))

(defn tasks-by-agent
  "Get all tasks assigned to the specified agent.

   Arguments:
   - agent-id: UUID of the agent

   Returns a vector of tasks assigned to this agent."
  [agent-id]
  (core/tasks-by-agent agent-id))

(defn tasks-by-type
  "Get all tasks of the specified type.

   Arguments:
   - task-type: One of :plan :design :implement :test :review :deploy

   Returns a vector of matching tasks."
  [task-type]
  (core/tasks-by-type task-type))

(defn all-tasks
  "Get all tasks in the store."
  []
  (core/all-tasks))

;------------------------------------------------------------------------------ Layer 3
;; Task decomposition

(defn decompose-task
  "Decompose a parent task into sub-tasks.
   Creates parent/child relationships between tasks.

   Arguments:
   - parent-task-id: UUID of the parent task
   - sub-tasks: Vector of task data maps to create as children
   - logger: Optional logger

   Returns the updated parent task with :task/children populated.

   Example:
     (decompose-task parent-id
                     [{:task/type :design}
                      {:task/type :implement}
                      {:task/type :test}])"
  ([parent-task-id sub-tasks] (core/decompose-task! parent-task-id sub-tasks))
  ([parent-task-id sub-tasks logger] (core/decompose-task! parent-task-id sub-tasks logger)))

(defn get-children
  "Get all child tasks of the specified task."
  [task-id]
  (core/get-children task-id))

(defn get-parent
  "Get the parent task of the specified task, or nil if no parent."
  [task-id]
  (core/get-parent task-id))

(defn get-root-task
  "Get the top-level ancestor task of the specified task.
   Returns the task itself if it has no parent."
  [task-id]
  (core/get-root-task task-id))

(defn all-children-completed?
  "Check if all children of a task are completed."
  [task-id]
  (core/all-children-completed? task-id))

;------------------------------------------------------------------------------ Layer 4
;; Priority queue operations

(defn create-queue
  "Create a new priority queue for task scheduling.

   Returns an atom containing the queue state."
  []
  (queue/create-queue))

(defn enqueue
  "Add a task to the queue.

   Arguments:
   - queue: The queue atom
   - task: The task to add
   - opts: Optional map with :ready? (boolean) and :workflow-priority (0-10)

   Returns the task.

   Example:
     (enqueue q task {:workflow-priority 8 :ready? true})"
  ([queue task] (queue/enqueue queue task))
  ([queue task opts] (queue/enqueue queue task opts)))

(defn dequeue
  "Remove and return the highest priority task.

   Arguments:
   - queue: The queue atom
   - role: Optional agent role to filter by (:planner :implementer etc.)

   Returns the task or nil if no matching task available."
  ([queue] (queue/dequeue queue))
  ([queue role] (queue/dequeue queue role)))

(defn peek-queue
  "Return the highest priority task without removing it.

   Arguments:
   - queue: The queue atom
   - role: Optional agent role to filter by

   Returns the task or nil if queue is empty."
  ([queue] (queue/peek-queue queue))
  ([queue role] (queue/peek-queue queue role)))

(defn queue-size
  "Return the number of tasks in the queue."
  [queue]
  (queue/queue-size queue))

(defn queue-empty?
  "Return true if the queue is empty."
  [queue]
  (queue/queue-empty? queue))

(defn remove-from-queue
  "Remove a specific task from the queue by ID.
   Returns the removed task or nil if not found."
  [queue task-id]
  (queue/remove-from-queue queue task-id))

(defn update-priority
  "Update the priority of a task in the queue.

   Arguments:
   - queue: The queue atom
   - task-id: ID of the task to update
   - opts: Map with :ready? and/or :workflow-priority"
  [queue task-id opts]
  (queue/update-priority queue task-id opts))

(defn tasks-in-queue
  "Return all tasks currently in the queue, sorted by priority (highest first)."
  [queue]
  (queue/tasks-in-queue queue))

(defn clear-queue
  "Remove all tasks from the queue."
  [queue]
  (queue/clear-queue queue))

;------------------------------------------------------------------------------ Layer 5
;; Dependency graph operations

(defn create-graph
  "Create a new dependency graph.

   Returns an atom containing the graph state."
  []
  (graph/create-graph))

(defn add-dependency
  "Add a dependency: child depends on parent.
   The child task cannot start until the parent is completed.

   Arguments:
   - graph: The graph atom
   - parent-id: UUID of the parent task
   - child-id: UUID of the child task
   - logger: Optional logger

   Returns true if dependency was added, false if it would create a cycle."
  ([graph parent-id child-id] (graph/add-dependency graph parent-id child-id))
  ([graph parent-id child-id logger] (graph/add-dependency graph parent-id child-id logger)))

(defn remove-dependency
  "Remove a dependency between parent and child."
  ([graph parent-id child-id] (graph/remove-dependency graph parent-id child-id))
  ([graph parent-id child-id logger] (graph/remove-dependency graph parent-id child-id logger)))

(defn get-dependencies
  "Get all task IDs that the specified task depends on.
   These are tasks that must complete before this task can start."
  [graph task-id]
  (graph/get-dependencies graph task-id))

(defn get-dependents
  "Get all task IDs that depend on the specified task.
   These are tasks that cannot start until this task completes."
  [graph task-id]
  (graph/get-dependents graph task-id))

(defn register-task
  "Register a task in the graph without any dependencies.
   Useful for ensuring a task appears in graph queries."
  [graph task-id]
  (graph/register-task graph task-id))

(defn unregister-task
  "Remove a task from the graph, including all its dependencies."
  [graph task-id]
  (graph/unregister-task graph task-id))

(defn ready-tasks
  "Get all tasks that have no unsatisfied dependencies.

   Arguments:
   - graph: The dependency graph
   - completed: Set of task IDs that are completed

   Returns a set of task IDs that are ready to run."
  [graph completed]
  (graph/ready-tasks graph completed))

(defn blocked-tasks
  "Get all tasks that have unsatisfied dependencies.

   Arguments:
   - graph: The dependency graph
   - completed: Set of task IDs that are completed

   Returns a set of task IDs that are blocked."
  [graph completed]
  (graph/blocked-tasks graph completed))

(defn topological-sort
  "Return tasks in topological order (respecting dependencies).
   Tasks with no dependencies come first.

   Returns nil if the graph contains a cycle."
  [graph]
  (graph/topological-sort graph))

(defn dependency-chain
  "Get the full chain of dependencies for a task (transitive closure).
   Returns all tasks that must complete before this task can start."
  [graph task-id]
  (graph/dependency-chain graph task-id))

(defn dependent-chain
  "Get the full chain of dependents for a task (transitive closure).
   Returns all tasks that directly or indirectly depend on this task."
  [graph task-id]
  (graph/dependent-chain graph task-id))

(defn critical-path
  "Find the longest dependency chain in the graph.
   Returns a vector of task IDs representing the critical path."
  [graph]
  (graph/critical-path graph))

(defn graph-stats
  "Return statistics about the dependency graph."
  [graph]
  (graph/graph-stats graph))

(defn clear-graph
  "Remove all tasks and dependencies from the graph."
  [graph]
  (graph/clear-graph graph))

;------------------------------------------------------------------------------ Layer 6
;; Store management (for testing)

(defn reset-store!
  "Reset the task store to empty. Useful for testing."
  []
  (core/reset-store!))

(defn get-store
  "Return the current state of the task store."
  []
  (core/get-store))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; --- Task CRUD ---
  (reset-store!)
  (def t1 (create-task {:task/type :implement}))
  (get-task (:task/id t1))
  (update-task (:task/id t1) {:task/constraints {:budget {:tokens 10000}}})

  ;; --- State machine ---
  (def agent-id (random-uuid))
  (start-task (:task/id t1) agent-id)
  (complete-task (:task/id t1) {:signals [:artifact-created]})

  ;; --- Queries ---
  (tasks-by-status :completed)
  (tasks-by-type :implement)

  ;; --- Decomposition ---
  (reset-store!)
  (def parent (create-task {:task/type :plan}))
  (decompose-task (:task/id parent)
                  [{:task/type :design}
                   {:task/type :implement}
                   {:task/type :test}])
  (get-children (:task/id parent))

  ;; --- Queue ---
  (def q (create-queue))
  (enqueue q {:task/id (random-uuid) :task/type :implement :task/status :pending}
           {:workflow-priority 5})
  (enqueue q {:task/id (random-uuid) :task/type :plan :task/status :pending}
           {:workflow-priority 8})
  (queue-size q)
  (dequeue q :planner)

  ;; --- Graph ---
  (def g (create-graph))
  (def a (random-uuid))
  (def b (random-uuid))
  (register-task g a)
  (register-task g b)
  (add-dependency g a b)  ; b depends on a
  (ready-tasks g #{})     ; => #{a}
  (ready-tasks g #{a})    ; => #{b}
  (topological-sort g)

  :leave-this-here)
