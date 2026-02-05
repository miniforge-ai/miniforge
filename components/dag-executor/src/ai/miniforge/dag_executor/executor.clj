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
;; Docker Image Management
;; ============================================================================

(def task-runner-images
  "Pre-defined task runner images that can be built from bundled Dockerfiles.
   Keys: :minimal (Alpine), :clojure (full tooling)"
  docker/task-runner-images)

(def image-exists?
  "Check if a Docker image exists locally.
   (image-exists? docker-path image-name) -> boolean"
  docker/image-exists?)

(def build-image!
  "Build a Docker image from a Dockerfile resource.
   (build-image! docker-path image-name dockerfile-resource-path) -> Result"
  docker/build-image!)

(def ensure-image!
  "Ensure a task runner image exists, building if necessary.
   (ensure-image! docker-path :minimal) -> Result
   (ensure-image! docker-path :clojure :force? true) -> Result"
  docker/ensure-image!)

(def ensure-all-images!
  "Ensure all task runner images are available.
   (ensure-all-images! docker-path) -> {:minimal Result :clojure Result}
   (ensure-all-images! docker-path :force? true) -> rebuilds all"
  docker/ensure-all-images!)

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

(defn prepare-docker-executor!
  "Create a Docker executor with images ensured to exist.

   This is the recommended way to set up Docker execution for deployments.
   It ensures the required task runner images are built before returning.

   Arguments:
   - config: Docker executor config
     {:image - image to use for tasks (default: miniforge/task-runner:latest)
      :image-type - which bundled image to ensure (:minimal or :clojure)
      :ensure-image? - whether to build image if missing (default: true)
      :docker-path - path to docker binary}

   Returns {:executor DockerExecutor :image-result Result} or error Result."
  [config]
  (let [docker-path (:docker-path config)
        image-type (or (:image-type config) :minimal)
        ensure? (get config :ensure-image? true)
        default-image (get-in task-runner-images [image-type :image])
        image (or (:image config) default-image)]
    ;; Ensure image exists if requested
    (if ensure?
      (let [image-result (ensure-image! docker-path image-type)]
        (if (result/ok? image-result)
          (result/ok {:executor (create-docker-executor (assoc config :image image))
                      :image-result image-result})
          image-result))
      (result/ok {:executor (create-docker-executor (assoc config :image image))
                  :image-result nil}))))

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
  ;; -------------------------------------------------------------------------
  ;; Image Management (prep step for Docker executor)
  ;; -------------------------------------------------------------------------

  ;; Check what images are available to build
  task-runner-images
  ;; => {:minimal {:image "miniforge/task-runner:latest" ...}
  ;;     :clojure {:image "miniforge/task-runner-clojure:latest" ...}}

  ;; Check if an image exists locally
  (image-exists? nil "miniforge/task-runner:latest")

  ;; Ensure a specific image (builds if missing)
  (ensure-image! nil :minimal)
  (ensure-image! nil :clojure)

  ;; Force rebuild even if exists
  (ensure-image! nil :clojure :force? true)

  ;; Ensure all images
  (ensure-all-images! nil)
  ;; => {:minimal {:ok? true ...} :clojure {:ok? true ...}}

  ;; -------------------------------------------------------------------------
  ;; Recommended: prepare-docker-executor! (handles image + executor setup)
  ;; -------------------------------------------------------------------------

  ;; Create executor with image automatically ensured
  (def prep-result (prepare-docker-executor! {:image-type :clojure}))
  (def executor (:executor (:data prep-result)))

  ;; -------------------------------------------------------------------------
  ;; Manual executor setup
  ;; -------------------------------------------------------------------------

  ;; Create executor registry
  (def executors (create-executor-registry
                  {:docker {:image "miniforge/task-runner-clojure:latest"}
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
