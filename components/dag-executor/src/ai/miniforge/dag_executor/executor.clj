(ns ai.miniforge.dag-executor.executor
  "Pluggable task execution backends.

   Provides a unified interface for task isolation using:
   - Kubernetes (K8s Jobs)
   - Docker containers
   - Local worktree with semaphores (fallback)

   The executor contract abstracts how tasks get isolated environments
   for code generation and PR lifecycle operations.

   See:
   - protocols/executor.clj - Protocol definition
   - protocols/impl/docker.clj - Docker implementation
   - protocols/impl/kubernetes.clj - Kubernetes implementation
   - protocols/impl/worktree.clj - Worktree fallback implementation"
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.docker :as docker]
   [ai.miniforge.dag-executor.protocols.impl.kubernetes :as k8s]
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]))

;; ============================================================================
;; Protocol Re-exports
;; ============================================================================

(def TaskExecutor
  "Protocol for task execution backends."
  proto/TaskExecutor)

(def executor-type proto/executor-type)
(def available? proto/available?)
(def acquire-environment! proto/acquire-environment!)
(def execute! proto/execute!)
(def copy-to! proto/copy-to!)
(def copy-from! proto/copy-from!)
(def release-environment! proto/release-environment!)
(def environment-status proto/environment-status)

(def create-environment-record
  "Create an environment record."
  proto/create-environment-record)

;; ============================================================================
;; Factory Re-exports
;; ============================================================================

(def create-docker-executor
  "Create a Docker executor.

   Config:
   - :image - Container image for tasks (default: alpine:latest)
   - :network - Docker network to attach to
   - :docker-path - Path to docker binary"
  docker/create-docker-executor)

(def create-kubernetes-executor
  "Create a Kubernetes executor.

   Config:
   - :namespace - K8s namespace (default: 'default')
   - :image - Container image for tasks (default: alpine:latest)
   - :kubectl-path - Path to kubectl binary"
  k8s/create-kubernetes-executor)

(def create-worktree-executor
  "Create a worktree-based executor (fallback).

   Config:
   - :base-path - Base directory for worktrees (default: /tmp/miniforge-worktrees)
   - :max-concurrent - Max concurrent worktrees (default: 4)"
  worktree/create-worktree-executor)

;; ============================================================================
;; Executor Selection
;; ============================================================================

(def executor-priority
  "Preferred executor order. First available wins."
  [:kubernetes :docker :worktree])

(defn select-executor
  "Select the best available executor from a registry.

   Arguments:
   - executors: Map of executor-type -> executor instance
   - preferred: Optional preferred executor type

   Returns the first available executor, or nil if none available."
  [executors & {:keys [preferred]}]
  (let [order (if preferred
                (cons preferred (remove #{preferred} executor-priority))
                executor-priority)]
    (->> order
         (map #(get executors %))
         (filter some?)
         (filter #(let [r (available? %)]
                    (and (result/ok? r)
                         (:available? (:data r)))))
         first)))

(defn create-executor-registry
  "Create a registry of available executors.

   Arguments:
   - config: Configuration map with executor-specific settings
     {:kubernetes {:namespace string :image string ...}
      :docker {:image string :network string ...}
      :worktree {:base-path string :max-concurrent int ...}}

   Returns map of executor-type -> executor instance."
  [config]
  (cond-> {}
    (:kubernetes config)
    (assoc :kubernetes (create-kubernetes-executor (:kubernetes config)))

    (:docker config)
    (assoc :docker (create-docker-executor (:docker config)))

    ;; Worktree is always available as fallback
    true
    (assoc :worktree (create-worktree-executor (or (:worktree config) {})))))

;; ============================================================================
;; High-level Helpers
;; ============================================================================

(defn with-environment
  "Execute a function within an acquired environment.

   Automatically acquires and releases the environment.

   Arguments:
   - executor: TaskExecutor instance
   - task-id: Task UUID
   - config: Environment config
   - f: Function (fn [env-record] ...) to execute

   Returns result from f, or error if acquisition fails."
  [executor task-id config f]
  (let [acquire-result (acquire-environment! executor task-id config)]
    (if (result/err? acquire-result)
      acquire-result
      (let [env (:data acquire-result)]
        (try
          (f env)
          (finally
            (release-environment! executor (:environment-id env))))))))

(defn clone-and-checkout!
  "Clone a repository and checkout a branch in the environment.

   Arguments:
   - executor: TaskExecutor instance
   - environment-id: Environment ID
   - repo-url: Git repository URL
   - branch: Branch to checkout
   - opts: Additional options {:depth :sparse-paths}

   Returns result with checkout info."
  [executor environment-id repo-url branch opts]
  (let [depth-args (when (:depth opts) ["--depth" (str (:depth opts))])
        clone-cmd (str "git clone "
                       (when depth-args (str (first depth-args) " " (second depth-args) " "))
                       repo-url " .")
        clone-result (execute! executor environment-id clone-cmd
                               {:timeout-ms 300000})]
    (if (and (result/ok? clone-result)
             (zero? (:exit-code (:data clone-result))))
      ;; Checkout branch
      (let [checkout-result (execute! executor environment-id
                                      (str "git checkout " branch)
                                      {:timeout-ms 60000})]
        (if (and (result/ok? checkout-result)
                 (zero? (:exit-code (:data checkout-result))))
          (result/ok {:cloned? true :branch branch})
          checkout-result))
      clone-result)))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Create executor registry
  (def executors (create-executor-registry
                  {:docker {:image "ubuntu:22.04"}
                   :worktree {:base-path "/tmp/mf-worktrees"}}))

  ;; Select best available
  (def executor (select-executor executors))
  (executor-type executor)  ; => :docker or :worktree

  ;; Check availability
  (available? executor)

  ;; Use environment
  (with-environment executor (random-uuid)
    {:repo-url "https://github.com/example/repo"
     :branch "main"}
    (fn [env]
      (execute! executor (:environment-id env)
                "ls -la"
                {:capture-output? true})))

  ;; Manual lifecycle
  (def env-result (acquire-environment! executor (random-uuid)
                                        {:branch "feat/test"}))
  (def env-id (:environment-id (:data env-result)))

  (execute! executor env-id "git status" {})
  (release-environment! executor env-id)

  :leave-this-here)
