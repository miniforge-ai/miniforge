(ns ai.miniforge.dag-executor.executor
  "Pluggable task execution backends.

   Defines the executor protocol and provides implementations for:
   - Kubernetes (K8s Jobs)
   - Docker containers
   - Local worktree with semaphores (fallback)

   The executor contract abstracts how tasks get isolated environments
   for code generation and PR lifecycle operations."
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Executor Protocol

(defprotocol TaskExecutor
  "Protocol for task execution backends.

   Implementations provide isolated environments where tasks can:
   - Clone/checkout repositories
   - Run code generation
   - Execute tests and validation
   - Create and push commits

   The protocol is designed to be backend-agnostic, supporting
   containers (Docker, K8s) and local worktree execution."

  (executor-type [this]
    "Return the executor type keyword: :kubernetes, :docker, :worktree")

  (available? [this]
    "Check if this executor backend is available.
     Returns result with {:available? bool :reason string}")

  (acquire-environment! [this task-id config]
    "Acquire an isolated execution environment for a task.

     Arguments:
     - task-id: UUID identifying the task
     - config: Environment configuration map
       {:repo-url string         ; Git repository URL
        :branch string           ; Branch to checkout
        :base-sha string         ; Base commit SHA
        :workdir string          ; Working directory inside environment
        :env map                 ; Environment variables
        :resources map           ; Resource limits {:cpu :memory}
        :timeout-ms long}        ; Acquisition timeout

     Returns result with:
     {:environment-id string    ; Unique ID for this environment
      :type keyword             ; :container, :worktree
      :workdir string           ; Path to working directory
      :metadata map}            ; Backend-specific metadata")

  (execute! [this environment-id command opts]
    "Execute a command in the environment.

     Arguments:
     - environment-id: ID from acquire-environment!
     - command: Command to execute (string or vector)
     - opts: Execution options
       {:timeout-ms long        ; Command timeout
        :env map                ; Additional env vars
        :workdir string         ; Override working directory
        :capture-output? bool}  ; Capture stdout/stderr

     Returns result with:
     {:exit-code int
      :stdout string            ; If capture-output?
      :stderr string            ; If capture-output?
      :duration-ms long}")

  (copy-to! [this environment-id local-path remote-path]
    "Copy files from host to environment.
     Returns result with {:copied-bytes long}")

  (copy-from! [this environment-id remote-path local-path]
    "Copy files from environment to host.
     Returns result with {:copied-bytes long}")

  (release-environment! [this environment-id]
    "Release and cleanup an execution environment.
     Returns result with {:released? bool}")

  (environment-status [this environment-id]
    "Get status of an execution environment.
     Returns result with:
     {:status keyword           ; :running, :stopped, :error, :unknown
      :created-at inst
      :resource-usage map}"))

;------------------------------------------------------------------------------ Layer 0
;; Environment record

(defrecord ExecutionEnvironment
  [environment-id
   executor-type
   task-id
   workdir
   repo-url
   branch
   created-at
   status
   metadata])

(defn create-environment-record
  "Create an environment record."
  [environment-id executor-type task-id workdir opts]
  (map->ExecutionEnvironment
   {:environment-id environment-id
    :executor-type executor-type
    :task-id task-id
    :workdir workdir
    :repo-url (:repo-url opts)
    :branch (:branch opts)
    :created-at (java.util.Date.)
    :status :running
    :metadata (:metadata opts {})}))

;------------------------------------------------------------------------------ Layer 1
;; Executor selection and factory

;; Forward declarations for factory functions
(declare create-kubernetes-executor create-docker-executor create-worktree-executor)

(def executor-priority
  "Preferred executor order. First available wins."
  [:kubernetes :docker :worktree])

(defn select-executor
  "Select the best available executor from a list.

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
         (filter #(let [result (available? %)]
                    (and (result/ok? result)
                         (:available? (:data result)))))
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
  ;; Implementations are created lazily - see create-* functions below
  (cond-> {}
    (:kubernetes config)
    (assoc :kubernetes (create-kubernetes-executor (:kubernetes config)))

    (:docker config)
    (assoc :docker (create-docker-executor (:docker config)))

    ;; Worktree is always available as fallback
    true
    (assoc :worktree (create-worktree-executor (:worktree config {})))))

;------------------------------------------------------------------------------ Layer 2
;; Kubernetes Executor

(defrecord KubernetesExecutor [config namespace image kubectl-path]
  TaskExecutor

  (executor-type [_this] :kubernetes)

  (available? [_this]
    ;; Check if kubectl is available and can connect to cluster
    (try
      (let [result (shell/sh
                    (or kubectl-path "kubectl")
                    "cluster-info" "--request-timeout=5s")]
        (if (zero? (:exit result))
          (result/ok {:available? true
                      :cluster-info (subs (:out result) 0 (min 200 (count (:out result))))})
          (result/ok {:available? false
                      :reason (:err result)})))
      (catch Exception e
        (result/ok {:available? false
                    :reason (.getMessage e)}))))

  (acquire-environment! [_this task-id env-config]
    (let [job-name (str "miniforge-task-" (subs (str task-id) 0 8))
          _pod-spec {:apiVersion "batch/v1"
                    :kind "Job"
                    :metadata {:name job-name
                               :namespace namespace
                               :labels {:app "miniforge"
                                        :task-id (str task-id)}}
                    :spec {:template
                           {:spec {:containers
                                   [{:name "task"
                                     :image image
                                     :workingDir (or (:workdir env-config) "/workspace")
                                     :env (mapv (fn [[k v]] {:name (name k) :value v})
                                                (:env env-config))
                                     :resources {:limits (:resources env-config)}}]
                                   :restartPolicy "Never"}}
                           :backoffLimit 0}}]
      ;; In real impl: kubectl apply -f <pod-spec>
      ;; For now, return a placeholder
      (result/ok (create-environment-record
                  job-name :kubernetes task-id
                  (or (:workdir env-config) "/workspace")
                  (assoc env-config :metadata {:job-name job-name
                                               :namespace namespace})))))

  (execute! [_this environment-id command opts]
    ;; kubectl exec into the pod
    (let [_timeout-sec (quot (or (:timeout-ms opts) 300000) 1000)
          cmd-args (if (string? command)
                     ["sh" "-c" command]
                     command)]
      (try
        (let [start-time (System/currentTimeMillis)
              result (apply shell/sh
                            (or kubectl-path "kubectl")
                            "exec" "-n" namespace environment-id "--"
                            cmd-args)]
          (result/ok {:exit-code (:exit result)
                      :stdout (:out result)
                      :stderr (:err result)
                      :duration-ms (- (System/currentTimeMillis) start-time)}))
        (catch Exception e
          (result/err :exec-failed (.getMessage e))))))

  (copy-to! [_this environment-id local-path remote-path]
    (try
      (let [result (shell/sh
                    (or kubectl-path "kubectl")
                    "cp" local-path
                    (str namespace "/" environment-id ":" remote-path))]
        (if (zero? (:exit result))
          (result/ok {:copied-bytes -1}) ; kubectl cp doesn't report bytes
          (result/err :copy-failed (:err result))))
      (catch Exception e
        (result/err :copy-failed (.getMessage e)))))

  (copy-from! [_this environment-id remote-path local-path]
    (try
      (let [result (shell/sh
                    (or kubectl-path "kubectl")
                    "cp"
                    (str namespace "/" environment-id ":" remote-path)
                    local-path)]
        (if (zero? (:exit result))
          (result/ok {:copied-bytes -1})
          (result/err :copy-failed (:err result))))
      (catch Exception e
        (result/err :copy-failed (.getMessage e)))))

  (release-environment! [_this environment-id]
    (try
      (let [result (shell/sh
                    (or kubectl-path "kubectl")
                    "delete" "job" environment-id "-n" namespace)]
        (result/ok {:released? (zero? (:exit result))}))
      (catch Exception e
        (result/err :release-failed (.getMessage e)))))

  (environment-status [_this environment-id]
    (try
      (let [result (shell/sh
                    (or kubectl-path "kubectl")
                    "get" "job" environment-id "-n" namespace
                    "-o" "jsonpath={.status.conditions[0].type}")]
        (result/ok {:status (case (:out result)
                              "Complete" :stopped
                              "Failed" :error
                              :running)
                    :raw (:out result)}))
      (catch Exception e
        (result/ok {:status :unknown :error (.getMessage e)})))))

(defn create-kubernetes-executor
  "Create a Kubernetes executor.

   Config:
   - :namespace - K8s namespace (default: 'miniforge')
   - :image - Container image for tasks
   - :kubectl-path - Path to kubectl binary"
  [config]
  (map->KubernetesExecutor
   {:config config
    :namespace (or (:namespace config) "miniforge")
    :image (or (:image config) "miniforge/task-runner:latest")
    :kubectl-path (:kubectl-path config)}))

;------------------------------------------------------------------------------ Layer 2
;; Docker Executor

(defrecord DockerExecutor [config image network docker-path]
  TaskExecutor

  (executor-type [_this] :docker)

  (available? [_this]
    (try
      (let [result (shell/sh
                    (or docker-path "docker")
                    "info" "--format" "{{.ServerVersion}}")]
        (if (zero? (:exit result))
          (result/ok {:available? true
                      :docker-version (clojure.string/trim (:out result))})
          (result/ok {:available? false
                      :reason (:err result)})))
      (catch Exception e
        (result/ok {:available? false
                    :reason (.getMessage e)}))))

  (acquire-environment! [_this task-id env-config]
    (let [container-name (str "miniforge-task-" (subs (str task-id) 0 8))
          workdir (or (:workdir env-config) "/workspace")
          env-args (mapcat (fn [[k v]] ["-e" (str (name k) "=" v)])
                           (:env env-config))
          resource-args (cond-> []
                          (get-in env-config [:resources :memory])
                          (into ["--memory" (get-in env-config [:resources :memory])])

                          (get-in env-config [:resources :cpu])
                          (into ["--cpus" (str (get-in env-config [:resources :cpu]))]))
          cmd-args (concat [(or docker-path "docker") "run" "-d"
                            "--name" container-name
                            "-w" workdir]
                           (when network ["--network" network])
                           env-args
                           resource-args
                           [image "sleep" "infinity"])]
      (try
        (let [result (apply shell/sh cmd-args)]
          (if (zero? (:exit result))
            (let [container-id (clojure.string/trim (:out result))]
              (result/ok (create-environment-record
                          container-name :docker task-id workdir
                          (assoc env-config
                                 :metadata {:container-id container-id
                                            :container-name container-name}))))
            (result/err :container-create-failed (:err result))))
        (catch Exception e
          (result/err :container-create-failed (.getMessage e))))))

  (execute! [_this environment-id command opts]
    (let [cmd-args (if (string? command)
                     ["sh" "-c" command]
                     command)
          workdir-args (when (:workdir opts)
                         ["-w" (:workdir opts)])
          env-args (mapcat (fn [[k v]] ["-e" (str (name k) "=" v)])
                           (:env opts))
          full-args (concat [(or docker-path "docker") "exec"]
                            workdir-args
                            env-args
                            [environment-id]
                            cmd-args)]
      (try
        (let [start-time (System/currentTimeMillis)
              result (apply shell/sh full-args)]
          (result/ok {:exit-code (:exit result)
                      :stdout (:out result)
                      :stderr (:err result)
                      :duration-ms (- (System/currentTimeMillis) start-time)}))
        (catch Exception e
          (result/err :exec-failed (.getMessage e))))))

  (copy-to! [_this environment-id local-path remote-path]
    (try
      (let [result (shell/sh
                    (or docker-path "docker")
                    "cp" local-path
                    (str environment-id ":" remote-path))]
        (if (zero? (:exit result))
          (result/ok {:copied-bytes -1})
          (result/err :copy-failed (:err result))))
      (catch Exception e
        (result/err :copy-failed (.getMessage e)))))

  (copy-from! [_this environment-id remote-path local-path]
    (try
      (let [result (shell/sh
                    (or docker-path "docker")
                    "cp"
                    (str environment-id ":" remote-path)
                    local-path)]
        (if (zero? (:exit result))
          (result/ok {:copied-bytes -1})
          (result/err :copy-failed (:err result))))
      (catch Exception e
        (result/err :copy-failed (.getMessage e)))))

  (release-environment! [_this environment-id]
    (try
      ;; Stop and remove container
      (shell/sh (or docker-path "docker") "stop" "-t" "5" environment-id)
      (let [result (shell/sh (or docker-path "docker") "rm" "-f" environment-id)]
        (result/ok {:released? (zero? (:exit result))}))
      (catch Exception e
        (result/err :release-failed (.getMessage e)))))

  (environment-status [_this environment-id]
    (try
      (let [result (shell/sh
                    (or docker-path "docker")
                    "inspect" environment-id
                    "--format" "{{.State.Status}}")]
        (if (zero? (:exit result))
          (result/ok {:status (case (clojure.string/trim (:out result))
                                "running" :running
                                "exited" :stopped
                                "dead" :error
                                :unknown)})
          (result/ok {:status :unknown :error (:err result)})))
      (catch Exception e
        (result/ok {:status :unknown :error (.getMessage e)})))))

(defn create-docker-executor
  "Create a Docker executor.

   Config:
   - :image - Container image for tasks
   - :network - Docker network to attach to
   - :docker-path - Path to docker binary"
  [config]
  (map->DockerExecutor
   {:config config
    :image (or (:image config) "miniforge/task-runner:latest")
    :network (:network config)
    :docker-path (:docker-path config)}))

;------------------------------------------------------------------------------ Layer 2
;; Worktree Executor (Fallback)

(defrecord WorktreeExecutor [config base-path max-concurrent lock-pool]
  TaskExecutor

  (executor-type [_this] :worktree)

  (available? [_this]
    ;; Worktree executor is always available if git is installed
    (try
      (let [result (shell/sh "git" "--version")]
        (if (zero? (:exit result))
          (result/ok {:available? true
                      :git-version (clojure.string/trim (:out result))})
          (result/ok {:available? false
                      :reason "git not found"})))
      (catch Exception e
        (result/ok {:available? false
                    :reason (.getMessage e)}))))

  (acquire-environment! [_this task-id env-config]
    ;; Create a git worktree for the task
    (let [worktree-name (str "task-" (subs (str task-id) 0 8))
          worktree-path (str base-path "/" worktree-name)
          repo-path (or (:repo-path env-config) ".")
          branch (or (:branch env-config) "main")]
      (try
        ;; Create worktree directory
        (.mkdirs (java.io.File. base-path))

        ;; Add git worktree
        (let [result (shell/sh
                      "git" "-C" repo-path
                      "worktree" "add" "-b" worktree-name
                      worktree-path branch)]
          (if (zero? (:exit result))
            (result/ok (create-environment-record
                        worktree-name :worktree task-id worktree-path
                        (assoc env-config
                               :metadata {:worktree-path worktree-path
                                          :repo-path repo-path})))
            ;; Try without -b if branch already exists
            (let [result2 (shell/sh
                           "git" "-C" repo-path
                           "worktree" "add" worktree-path branch)]
              (if (zero? (:exit result2))
                (result/ok (create-environment-record
                            worktree-name :worktree task-id worktree-path
                            (assoc env-config
                                   :metadata {:worktree-path worktree-path
                                              :repo-path repo-path})))
                (result/err :worktree-create-failed (:err result2))))))
        (catch Exception e
          (result/err :worktree-create-failed (.getMessage e))))))

  (execute! [_this environment-id command opts]
    ;; Execute command in the worktree directory
    (let [worktree-path (str base-path "/" environment-id)
          cmd-str (if (string? command)
                    command
                    (clojure.string/join " " command))
          env-map (merge {} (:env opts))]
      (try
        (let [start-time (System/currentTimeMillis)
              pb (ProcessBuilder. ["sh" "-c" cmd-str])
              _ (.directory pb (java.io.File. (or (:workdir opts) worktree-path)))
              _ (when (seq env-map)
                  (let [pb-env (.environment pb)]
                    (doseq [[k v] env-map]
                      (.put pb-env (name k) (str v)))))
              process (.start pb)
              stdout (slurp (.getInputStream process))
              stderr (slurp (.getErrorStream process))
              exit-code (.waitFor process)]
          (result/ok {:exit-code exit-code
                      :stdout stdout
                      :stderr stderr
                      :duration-ms (- (System/currentTimeMillis) start-time)}))
        (catch Exception e
          (result/err :exec-failed (.getMessage e))))))

  (copy-to! [_this environment-id local-path remote-path]
    ;; For worktree, just copy files
    (let [worktree-path (str base-path "/" environment-id)
          dest-path (str worktree-path "/" remote-path)]
      (try
        (let [source (java.io.File. local-path)
              dest (java.io.File. dest-path)]
          (.mkdirs (.getParentFile dest))
          (java.nio.file.Files/copy
           (.toPath source)
           (.toPath dest)
           (into-array java.nio.file.CopyOption
                       [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          (result/ok {:copied-bytes (.length source)}))
        (catch Exception e
          (result/err :copy-failed (.getMessage e))))))

  (copy-from! [_this environment-id remote-path local-path]
    (let [worktree-path (str base-path "/" environment-id)
          source-path (str worktree-path "/" remote-path)]
      (try
        (let [source (java.io.File. source-path)
              dest (java.io.File. local-path)]
          (.mkdirs (.getParentFile dest))
          (java.nio.file.Files/copy
           (.toPath source)
           (.toPath dest)
           (into-array java.nio.file.CopyOption
                       [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          (result/ok {:copied-bytes (.length source)}))
        (catch Exception e
          (result/err :copy-failed (.getMessage e))))))

  (release-environment! [_this environment-id]
    ;; Remove the git worktree
    (let [worktree-path (str base-path "/" environment-id)]
      (try
        ;; First, try to find the main repo by checking metadata
        ;; For now, use the worktree to find its main repo
        (let [result (shell/sh
                      "git" "-C" worktree-path
                      "worktree" "remove" "--force" worktree-path)]
          (if (zero? (:exit result))
            (result/ok {:released? true})
            ;; Fallback: just delete the directory
            (do
              (shell/sh "rm" "-rf" worktree-path)
              (result/ok {:released? true}))))
        (catch Exception e
          (result/err :release-failed (.getMessage e))))))

  (environment-status [_this environment-id]
    (let [worktree-path (str base-path "/" environment-id)]
      (if (.exists (java.io.File. worktree-path))
        (result/ok {:status :running})
        (result/ok {:status :stopped})))))

(defn create-worktree-executor
  "Create a worktree-based executor (fallback).

   Config:
   - :base-path - Base directory for worktrees (default: /tmp/miniforge-worktrees)
   - :max-concurrent - Max concurrent worktrees"
  [config]
  (map->WorktreeExecutor
   {:config config
    :base-path (or (:base-path config) "/tmp/miniforge-worktrees")
    :max-concurrent (or (:max-concurrent config) 4)
    :lock-pool (atom {})}))

;------------------------------------------------------------------------------ Layer 3
;; High-level execution helpers

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
        clone-cmd (concat ["git" "clone"]
                          depth-args
                          [repo-url "."])
        clone-result (execute! executor environment-id
                                 (clojure.string/join " " clone-cmd)
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

;------------------------------------------------------------------------------ Rich Comment
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
