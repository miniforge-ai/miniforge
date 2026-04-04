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
        image-type (get config :image-type :minimal)
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

(defn capture-provenance
  "Build a provenance record from a task execution result.

   Arguments:
   - task-id: String task identifier
   - executor: TaskExecutor instance (used to derive executor-type and image-digest)
   - exec-result: Map with execution outcome keys:
       :commands-executed  - vector of command strings that were run
       :started-at         - java.time.Instant when execution began
       :completed-at       - java.time.Instant when execution finished
       :exit-code          - integer exit code from the last command
       :stdout             - full stdout string (will be truncated to 500 chars)
       :stderr             - full stderr string (will be truncated to 500 chars)
       :image-digest       - (optional) OCI image digest string
       :environment-id     - (optional) environment identifier string

   Returns a provenance map conforming to the DAG-executor provenance record shape."
  [task-id executor exec-result]
  (let [{:keys [commands-executed started-at completed-at
                exit-code stdout stderr
                image-digest environment-id]} exec-result
        start-ms  (when started-at
                    (.toEpochMilli ^java.time.Instant started-at))
        end-ms    (when completed-at
                    (.toEpochMilli ^java.time.Instant completed-at))
        duration  (if (and start-ms end-ms) (- end-ms start-ms) 0)]
    {:provenance/task-id          (str task-id)
     :provenance/executor-type    (executor-type executor)
     :provenance/image-digest     image-digest
     :provenance/commands-executed (vec (or commands-executed []))
     :provenance/started-at       started-at
     :provenance/completed-at     completed-at
     :provenance/duration-ms      duration
     :provenance/exit-code        (or exit-code -1)
     :provenance/stdout-summary   (subs (or stdout "") 0 (min 500 (count (or stdout ""))))
     :provenance/stderr-summary   (subs (or stderr "") 0 (min 500 (count (or stderr ""))))
     :provenance/environment-id   (str (or environment-id ""))}))

(defn with-provenance
  "Wraps `with-environment`, capturing a provenance record after task execution.

   Behaves identically to `with-environment` but records timing, exit code, and
   stdout/stderr from the final execute! call made inside f.

   f receives [env-record prov-atom] where prov-atom is an atom the caller may
   populate with the raw execute! result data.  After f returns, provenance is
   captured from @prov-atom and attached to the return value.

   Arguments:
   - executor: TaskExecutor instance
   - task-id: Task UUID (also used as the provenance task-id)
   - config: Environment config (same as with-environment)
   - f: Function (fn [env prov-atom] ...) to execute
        The function should reset! prov-atom with a map containing at minimum:
          :commands-executed, :started-at, :completed-at, :exit-code,
          :stdout, :stderr  (all optional; missing keys default gracefully)

   Returns {:result <value returned by f> :provenance <provenance-record>}
   or an error result if environment acquisition fails."
  [executor task-id config f]
  (let [prov-atom (atom {})
        inner-result (with-environment executor task-id config
                       (fn [env] (f env prov-atom)))]
    {:result     inner-result
     :provenance (capture-provenance task-id executor @prov-atom)}))


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

  ;; -------------------------------------------------------------------------
  ;; Provenance capture
  ;; -------------------------------------------------------------------------

  ;; Build a provenance record from raw execution data
  (capture-provenance "task-abc" executor
                      {:commands-executed ["git clone ..." "ls -la"]
                       :started-at        (java.time.Instant/now)
                       :completed-at      (java.time.Instant/now)
                       :exit-code         0
                       :stdout            "total 8\n..."
                       :stderr            ""
                       :image-digest      "sha256:abc123"
                       :environment-id    "env-42"})

  ;; Run a task with automatic provenance capture
  (def prov-result
    (with-provenance executor (random-uuid)
      {:repo-url "https://github.com/example/repo" :branch "main"}
      (fn [env prov-atom]
        (let [started (java.time.Instant/now)
              r       (execute! executor (:environment-id env) "ls -la" {})]
          (reset! prov-atom {:commands-executed ["ls -la"]
                             :started-at        started
                             :completed-at      (java.time.Instant/now)
                             :exit-code         (:exit-code (:data r))
                             :stdout            (:stdout (:data r))
                             :stderr            (:stderr (:data r))
                             :environment-id    (:environment-id env)})
          r))))
  ;; => {:result {:ok? true :data {...}} :provenance {:provenance/task-id "..." ...}}

  ;; Manual lifecycle
  (def env-result (acquire-environment! executor (random-uuid)
                                        {:branch "feat/test"}))
  (def env-id (:environment-id (:data env-result)))

  (execute! executor env-id "git status" {})
  (release-environment! executor env-id)

  :leave-this-here)
