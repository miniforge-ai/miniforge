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

(ns ai.miniforge.dag-executor.interface
  "Public API for the DAG executor component.

   Provides DAG-based task execution with:
   - Dependency ordering and parallelism
   - Task workflow state machine
   - Budget and checkpoint management
   - Integration with domain-specific lifecycle events
   - Pluggable execution backends (K8s, Docker, worktree)

   A DAG node is not 'generate code once' - it's a task workflow that runs
   until it reaches a profile-defined terminal success state."
  (:require
   [ai.miniforge.dag-executor.branch-registry :as branch-registry]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.state-profile :as state-profile]
   [ai.miniforge.dag-executor.state :as state]
   [ai.miniforge.dag-executor.parallel :as parallel]
   [ai.miniforge.dag-executor.scheduler :as scheduler]
   [ai.miniforge.dag-executor.executor :as executor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.registry :as registry]
   [ai.miniforge.dag-executor.protocols.impl.runtime.selector :as selector]))

;------------------------------------------------------------------------------ Layer 0
;; Result helpers (re-exports)

(def ok
  "Create a success result.
   Example: (ok {:task-id id :status :merged})"
  result/ok)

(def err
  "Create an error result.
   Example: (err :task-not-found \"Task xyz not found\" {:task-id \"xyz\"})"
  result/err)

(def ok?
  "Check if a result is successful."
  result/ok?)

(def err?
  "Check if a result is an error."
  result/err?)

(def unwrap
  "Extract data from an ok result, throw if error."
  result/unwrap)

(def unwrap-or
  "Extract data from an ok result, or return default if error."
  result/unwrap-or)

;------------------------------------------------------------------------------ Layer 1
;; Task workflow state

(def build-state-profile
  "Build a custom state profile map."
  state-profile/build-profile)

(def build-state-profile-provider
  "Build a state-profile provider from profile definitions or resource-path entries."
  state-profile/build-provider)

(defn resolve-state-profile
  "Resolve a state profile keyword or inline map.

   With one argument, resolves against the kernel provider.
   With two arguments, resolves against the supplied provider."
  ([profile]
   (state-profile/resolve-profile profile))
  ([provider profile]
   (state-profile/resolve-profile provider profile)))

(defn available-state-profile-ids
  "List the state profile ids exposed by the kernel provider or a supplied provider."
  ([] (state-profile/available-profile-ids))
  ([provider] (state-profile/available-profile-ids provider)))

(def task-statuses
  "Valid task workflow statuses for the kernel default profile."
  state/task-statuses)

(def terminal-statuses
  "Terminal statuses for the kernel default profile."
  state/terminal-statuses)

(def run-statuses
  "Valid DAG run statuses: :pending :running :paused :completed :failed :partial"
  state/run-statuses)

(defn create-task-state
  "Create initial task workflow state.

   Arguments:
   - task-id: UUID of the task
   - deps: Set of task IDs this task depends on

   Options:
   - :max-fix-iterations - Max fix attempts (default 5)
   - :max-ci-retries - Max CI retry attempts (default 3)
   - :state-profile-provider - Provider map for keyword profile lookup

   Example:
     (create-task-state (random-uuid) #{parent-task-id}
                        :max-fix-iterations 3)"
  [task-id deps & opts]
  (apply state/create-task-state task-id deps opts))

(defn create-run-state
  "Create initial DAG run state.

   Arguments:
   - dag-id: UUID of the DAG definition
   - tasks: Map of task-id -> TaskWorkflowState

   Options:
   - :budget - Budget constraints map with :max-tokens :max-cost-usd :max-duration-ms
   - :state-profile-provider - Provider map for keyword profile lookup

   Example:
     (create-run-state dag-id
                       {task-a-id task-a task-b-id task-b}
                       :budget {:max-tokens 1000000 :max-cost-usd 50.0})"
  [dag-id tasks & opts]
  (apply state/create-run-state dag-id tasks opts))

(def create-run-atom
  "Create an atom containing run state for mutable operations."
  state/create-run-atom)

(def terminal?
  "Check if a status is terminal (no further transitions)."
  state/terminal?)

(def valid-transition?
  "Check if a state transition is valid."
  state/valid-transition?)

;------------------------------------------------------------------------------ Layer 2
;; State transitions

(def transition-task!
  "Atomically transition a task to a new status within a run atom.
   Returns result with updated run state or error.

   Example:
     (transition-task! run-atom task-id :implementing logger)"
  state/transition-task!)

(def mark-merged!
  "Atomically mark a task as merged in a run atom.

   Example:
     (mark-merged! run-atom task-id logger)"
  state/mark-merged!)

(def mark-completed!
  "Atomically mark a task as completed in a run atom using the run profile's
   success terminal status."
  state/mark-completed!)

(def mark-failed!
  "Atomically mark a task as failed in a run atom.

   Example:
     (mark-failed! run-atom task-id {:reason :ci-failed} logger)"
  state/mark-failed!)

(def update-task!
  "Atomically update a task within a run atom.

   Example:
     (update-task! run-atom task-id #(assoc % :task/pr pr-info) logger)"
  state/update-task!)

(def update-metrics!
  "Atomically update run metrics.

   Example:
     (update-metrics! run-atom {:total-tokens 1000 :total-cost-usd 0.05} logger)"
  state/update-metrics!)

;------------------------------------------------------------------------------ Layer 3
;; State queries

(def ready-tasks
  "Get all tasks that are ready to start (dependencies satisfied).
   Returns set of task IDs.

   Example:
     (ready-tasks @run-atom)  ; => #{task-a-id task-b-id}"
  state/ready-tasks)

(def running-tasks
  "Get all tasks currently in progress (not pending, not terminal).
   Returns set of task IDs."
  state/running-tasks)

(def blocked-tasks
  "Get all tasks blocked on dependencies.
   Returns map of task-id -> set of blocking task IDs."
  state/blocked-tasks)

(def all-terminal?
  "Check if all tasks are in terminal states."
  state/all-terminal?)

(def compute-run-status
  "Compute the appropriate run status based on task states.
   Returns :completed :partial :failed or :running."
  state/compute-run-status)

;------------------------------------------------------------------------------ Layer 4
;; Parallelism and resource locks

(def create-lock-pool
  "Create a lock pool for managing concurrent resource access.

   Options:
   - :max-repo-writes - Max concurrent repo writes (default 1 for safety)
   - :max-worktrees - Max concurrent worktrees (default 4)

   Example:
     (create-lock-pool :max-repo-writes 1 :max-worktrees 8)"
  parallel/create-lock-pool)

(def acquire-repo-write!
  "Acquire exclusive repo write lock.
   Blocks until lock is available or timeout.

   Example:
     (acquire-repo-write! lock-pool task-id 30000 logger)"
  parallel/acquire-repo-write!)

(def release-repo-write!
  "Release repo write lock."
  parallel/release-repo-write!)

(def acquire-file-locks!
  "Acquire locks on specific files.
   Returns error if any files are already locked by another holder.

   Example:
     (acquire-file-locks! lock-pool task-id [\"src/foo.clj\"] logger)"
  parallel/acquire-file-locks!)

(def release-file-locks!
  "Release file locks for a holder."
  parallel/release-file-locks!)

(def acquire-worktree!
  "Acquire a worktree slot for isolated task execution.

   Example:
     (acquire-worktree! lock-pool task-id 60000 logger)"
  parallel/acquire-worktree!)

(def release-worktree!
  "Release a worktree slot."
  parallel/release-worktree!)

(def release-all-locks!
  "Release all locks held by a task (cleanup on completion/failure)."
  parallel/release-all-locks!)

(def available-capacity
  "Get available capacity for parallel execution.
   Returns {:repo-write-available n :worktree-available n :current-file-locks n}"
  parallel/available-capacity)

(def select-parallel-batch
  "Select a batch of tasks that can run in parallel.
   Given ready tasks, returns subset that don't conflict with each other."
  parallel/select-parallel-batch)

;------------------------------------------------------------------------------ Layer 5
;; Scheduler

(def create-scheduler-context
  "Create context for the scheduling loop.

   Options:
   - :logger - Logger instance
   - :lock-pool - Lock pool (created if not provided)
   - :max-parallel - Max parallel tasks (default 4)
   - :checkpoint-interval-ms - Checkpoint interval (default 60000)
   - :execute-task-fn - Function (fn [task-id context] -> result)
   - :handle-pr-event-fn - Function (fn [event context] -> result)

   Example:
     (create-scheduler-context
       :logger my-logger
       :max-parallel 4
       :execute-task-fn my-executor)"
  scheduler/create-scheduler-context)

(def handle-task-event
  "Handle an event from the PR lifecycle controller.
   Updates task state based on the event type.

   Event actions: :pr-opened :ci-passed :ci-failed :review-approved
                  :review-changes-requested :fix-pushed :merge-ready
                  :merged :merge-failed

   Example:
     (handle-task-event run-atom
                        {:event/action :ci-passed :event/task-id task-id}
                        context)"
  scheduler/handle-task-event)

(def run-scheduler
  "Run the scheduler loop until completion or pause.

   Arguments:
   - run-atom: Atom containing run state
   - context: Scheduler context from create-scheduler-context

   Options:
   - :poll-interval-ms - Sleep between iterations (default 1000)
   - :on-iteration - Callback (fn [iteration-result]) for each iteration
   - :on-event - Callback (fn [event]) for task events

   Returns final run state.

   Example:
     (run-scheduler run-atom context
                    :poll-interval-ms 500
                    :on-iteration println)"
  scheduler/run-scheduler)

(def pause-scheduler
  "Signal the scheduler to pause at the next safe point."
  scheduler/pause-scheduler)

(def resume-scheduler
  "Resume a paused scheduler run."
  scheduler/resume-scheduler)

(def schedule-iteration
  "Perform one iteration of the scheduling loop.
   Useful for testing or custom scheduling logic.

   Returns {:continue? bool :run-state state :dispatched [task-ids]}"
  scheduler/schedule-iteration)

;------------------------------------------------------------------------------ Layer 6
;; Execution backends (K8s, Docker, Worktree)

(def TaskExecutor
  "Protocol for pluggable task execution backends.
   Implementations: KubernetesExecutor, DockerExecutor, WorktreeExecutor"
  executor/TaskExecutor)

(def create-executor-registry
  "Create a registry of available executors.

   Arguments:
   - config: Configuration map with executor-specific settings
     {:kubernetes {:namespace string :image string}
      :docker {:image string :network string}
      :worktree {:base-path string :max-concurrent int}}

   Returns map of executor-type -> executor instance."
  executor/create-executor-registry)

(def select-executor
  "Select the best available executor from a registry.
   Tries: kubernetes -> docker -> worktree (fallback).

   Options:
   - :preferred - Preferred executor type to try first"
  executor/select-executor)

(def create-kubernetes-executor
  "Create a Kubernetes executor for task isolation via K8s Jobs."
  executor/create-kubernetes-executor)

(def create-docker-executor
  "Create a Docker executor for task isolation via containers."
  executor/create-docker-executor)

(def create-worktree-executor
  "Create a worktree executor (fallback, no container isolation)."
  executor/create-worktree-executor)

(defn executor-type
  "Get the type of an executor (:kubernetes, :docker, :worktree)."
  [exec]
  (executor/executor-type exec))

(defn executor-available?
  "Check if an executor backend is available.
   Returns result with {:available? bool :reason string}"
  [exec]
  (executor/available? exec))

(defn acquire-environment!
  "Acquire an isolated execution environment for a task.

   Arguments:
   - executor: TaskExecutor instance
   - task-id: UUID identifying the task
   - config: Environment configuration
     {:repo-url string :branch string :env map :resources map}

   Returns result with environment record."
  [exec task-id config]
  (executor/acquire-environment! exec task-id config))

(defn release-environment!
  "Release and cleanup an execution environment."
  [exec environment-id]
  (executor/release-environment! exec environment-id))

(defn executor-execute!
  "Execute a command in an environment.

   Arguments:
   - executor: TaskExecutor instance
   - environment-id: ID from acquire-environment!
   - command: Command to execute (string or vector)
   - opts: {:timeout-ms long :env map :capture-output? bool}

   Returns result with {:exit-code int :stdout string :stderr string}"
  [exec environment-id command opts]
  (executor/execute! exec environment-id command opts))

(def execute!
  "Execute a command in an environment (alias for executor-execute!).
   See executor-execute! for full documentation."
  executor/execute!)

(def persist-workspace!
  "Persist workspace state from an execution environment."
  executor/persist-workspace!)

(def restore-workspace!
  "Restore workspace state into an execution environment."
  executor/restore-workspace!)

(def with-environment
  "Execute a function within an acquired environment.
   Automatically acquires and releases the environment.

   Example:
     (with-environment executor task-id {:branch \"main\"}
       (fn [env]
         (executor-execute! executor (:environment-id env)
                            \"make test\" {})))"
  executor/with-environment)

(def clone-and-checkout!
  "Clone a repository and checkout a branch in the environment."
  executor/clone-and-checkout!)

(def prepare-docker-executor!
  "Create a Docker executor with images ensured to exist.
   Returns {:executor DockerExecutor :image-result Result} or error Result."
  executor/prepare-docker-executor!)

(def ensure-image!
  "Ensure a task runner image exists, building if necessary.
   (ensure-image! docker-path :minimal) -> Result"
  executor/ensure-image!)

(def task-runner-images
  "Pre-defined task runner images (:minimal :clojure)."
  executor/task-runner-images)

;------------------------------------------------------------------------------ Layer 6.5
;; Runtime adapter (N11-delta) — descriptor / registry / selection

(def select-runtime
  "Resolve a runtime descriptor from config + host state per N11-delta §3.

   Returns a result map. On success, :data carries:
     {:descriptor       <runtime descriptor>
      :kind             <:docker | :podman | ...>
      :selection        :explicit | :auto-probe
      :runtime-version  <string>
      :probed           [<per-kind summary>] (auto-probe only)}

   On failure, :error :code is one of:
     :runtime/explicit-unsupported   (the named kind is not :supported?)
     :runtime/explicit-unavailable   (named kind probe failed)
     :runtime/none-available         (auto-probe found nothing)"
  selector/select-runtime)

(def runtime-probe-order
  "Auto-probe order applied when :runtime-kind is not configured."
  selector/probe-order)

(defn runtime-known-kinds
  "Set of all runtime kinds the registry knows about."
  []
  (registry/known-kinds))

(defn runtime-supported-kinds
  "Set of runtime kinds the executor can construct a working descriptor for."
  []
  (registry/supported-kinds))

(defn runtime-info
  "Probe a descriptor for availability and version. Returns
   {:available? bool :runtime-version str | :reason str}."
  [descriptor]
  (descriptor/runtime-info descriptor))

(defn runtime-executable
  "Return the resolved CLI binary for a descriptor."
  [descriptor]
  (descriptor/executable descriptor))

(defn runtime-kind
  "Return the runtime kind keyword for a descriptor."
  [descriptor]
  (descriptor/kind descriptor))

;------------------------------------------------------------------------------ Layer 7
;; Convenience functions

;------------------------------------------------------------------------------ Layer 8
;; Per-task branch registry — wires DAG dependency edges to scratch-worktree
;; bases so a task's agent sees its dependency's persisted output instead of
;; the spec branch. See branch_registry.clj for the design rationale.

(def create-branch-registry
  "Build an empty branch registry — `task-id → branch-info`."
  branch-registry/create-registry)

(def register-branch
  "Pure-data: associate `branch-info` with `task-id` in a registry value.
   The atom that holds the registry lives in the orchestrator; mutation
   happens via `(swap! reg register-branch ...)` at the call site."
  branch-registry/register-branch)

(def lookup-branch
  "Look up a task's persisted branch-info, or nil when unknown."
  branch-registry/lookup-branch)

(def resolve-base-branch
  "Decide a downstream task's scratch base: the dep's branch when there's
   exactly one registered, the default branch on zero deps or unregistered
   single dep, or an `:anomalies/dag-non-forest` map on multi-dep tasks."
  branch-registry/resolve-base-branch)

(def resolve-base-branch-error?
  "True when `resolve-base-branch` returned an anomaly map rather than a
   branch string. Lets callers branch on the result without coupling to
   the anomaly shape."
  branch-registry/resolve-error?)

(def validate-dag-forest
  "Plan-time validator: nil when every task has ≤1 dependency,
   `:anomalies/dag-non-forest` map otherwise.

   In v1 this was used as a hard plan-time gate. In v2 the gate is
   dropped — multi-parent DAGs are the normal path now (see
   I-DAG-MULTI-PARENT-MERGE.md). `validate-dag-forest` survives as an
   informational helper for plan-quality reporting."
  branch-registry/validate-forest)

(def dag-forest?
  "Predicate form of `validate-dag-forest`. Informational only in v2;
   see [[validate-dag-forest]]."
  branch-registry/forest?)

;------------------------------------------------------------------------------ Layer 9
;; Multi-parent base resolution primitives (v2)
;; Pure-data inputs to the orchestrator's git-using `merge-parent-branches!`.
;; See I-DAG-MULTI-PARENT-MERGE.md §3.2.

(def multi-parent?
  "True when a task's `:task/dependencies` declares more than one entry.
   Callers gate on this before choosing between [[resolve-base-branch]]
   (single-parent fast path, returns a string) and
   [[resolve-multi-parent-base]] (returns the v2 ordered-parents shape)."
  branch-registry/multi-parent?)

(def resolve-multi-parent-base
  "Build the ordered, SHA-pinned parent list for a multi-parent task.
   Returns `{:merge/parents [{:task/id :branch :sha :order}]}` in
   declaration order. The orchestrator's git layer is responsible for
   ancestor collapse (`merge-base --is-ancestor`); this returns the
   pre-collapse list."
  branch-registry/resolve-multi-parent-base)

(def collapse-duplicate-tips
  "Drop later parents whose `:sha` matches an earlier parent's `:sha`.
   Returns `{:parents [...] :collapsed [{:dropped :duplicate-of}]}`."
  branch-registry/collapse-duplicate-tips)

(def compute-merge-input-key
  "Deterministic idempotency hash from `[task-id, strategy, ordered
   parent SHAs]`. Used to name the merge ref under
   `refs/miniforge/dag-base/<run-id>/<task-id>/<input-key>`."
  branch-registry/compute-input-key)

(defn create-dag-from-tasks
  "Create a complete DAG run state from a sequence of task definitions.

   Arguments:
   - dag-id: UUID for the DAG
   - task-defs: Sequence of maps with :task/id and :task/deps

   Options:
   - :budget - Budget constraints
   - :state-profile - Task lifecycle profile keyword or map
   - :state-profile-provider - Provider map for keyword profile lookup
   - :max-fix-iterations - Default max fix iterations
   - :max-ci-retries - Default max CI retries

   Example:
     (create-dag-from-tasks
       (random-uuid)
       [{:task/id a-id :task/deps #{}}
        {:task/id b-id :task/deps #{a-id}}
        {:task/id c-id :task/deps #{a-id}}]
       :budget {:max-tokens 500000})"
  [dag-id task-defs & {:keys [budget state-profile state-profile-provider
                              max-fix-iterations max-ci-retries]
                       :or {max-fix-iterations 5 max-ci-retries 3}}]
  (let [tasks (->> task-defs
                   (map (fn [def]
                          [(:task/id def)
                           (create-task-state
                            (:task/id def)
                            (or (:task/deps def) #{})
                            :state-profile state-profile
                            :state-profile-provider state-profile-provider
                            :max-fix-iterations max-fix-iterations
                            :max-ci-retries max-ci-retries)]))
                   (into {}))]
    (create-run-state dag-id tasks
                      :budget budget
                      :state-profile state-profile
                      :state-profile-provider state-profile-provider)))

(defn execute-dag
  "Execute a DAG to completion.
   Convenience wrapper around create-run-atom and run-scheduler.

   Arguments:
   - dag-id: UUID for the DAG
   - task-defs: Sequence of task definitions
   - context-opts: Options for create-scheduler-context
   - run-opts: Options for run-scheduler

   Returns final run state.

   Example:
     (execute-dag
       (random-uuid)
       [{:task/id a-id :task/deps #{}}
        {:task/id b-id :task/deps #{a-id}}]
       {:logger my-logger :execute-task-fn my-executor}
       {:poll-interval-ms 500})"
  [dag-id task-defs context-opts run-opts]
  (let [run-state (create-dag-from-tasks dag-id task-defs
                                         :budget (:budget context-opts))
        run-atom (create-run-atom run-state)
        context (apply create-scheduler-context (mapcat identity context-opts))]
    (apply run-scheduler run-atom context (mapcat identity run-opts))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Quick example: Create and run a simple DAG
  (def a-id (random-uuid))
  (def b-id (random-uuid))
  (def c-id (random-uuid))

  ;; Create DAG: a -> b, a -> c (b and c can run in parallel after a)
  (def run-state
    (create-dag-from-tasks
     (random-uuid)
     [{:task/id a-id :task/deps #{}}
      {:task/id b-id :task/deps #{a-id}}
      {:task/id c-id :task/deps #{a-id}}]
     :budget {:max-tokens 100000}))

  ;; Check initial ready tasks
  (ready-tasks run-state)  ; => #{a-id}

  ;; Create run atom for mutable operations
  (def run-atom (create-run-atom run-state))

  ;; Transition task a through states
  (transition-task! run-atom a-id :ready nil)
  (transition-task! run-atom a-id :implementing nil)

  ;; Simulate completion
  (mark-merged! run-atom a-id nil)

  ;; Now b and c should be ready
  (ready-tasks @run-atom)  ; => #{b-id c-id}

  ;; Full execution would use:
  ;; (execute-dag dag-id task-defs
  ;;              {:logger logger :execute-task-fn my-impl}
  ;;              {:poll-interval-ms 1000})

  ;; Executor usage:
  ;; (def executors (create-executor-registry
  ;;                 {:docker {:image "miniforge/runner:latest"}}))
  ;; (def exec (select-executor executors))
  ;; (with-environment exec task-id {:branch "main"}
  ;;   (fn [env] (executor-execute! exec (:environment-id env) "make test" {})))

  :leave-this-here)
