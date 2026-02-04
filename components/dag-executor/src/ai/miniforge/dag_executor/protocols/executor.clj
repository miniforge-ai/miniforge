(ns ai.miniforge.dag-executor.protocols.executor
  "TaskExecutor protocol definition.

   Defines the contract for pluggable task execution backends that provide
   isolated environments for code generation and PR lifecycle operations.")

;; ============================================================================
;; TaskExecutor Protocol
;; ============================================================================

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

;; ============================================================================
;; Environment Record
;; ============================================================================

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
  [environment-id exec-type task-id workdir opts]
  (map->ExecutionEnvironment
   {:environment-id environment-id
    :executor-type exec-type
    :task-id task-id
    :workdir workdir
    :repo-url (:repo-url opts)
    :branch (:branch opts)
    :created-at (java.util.Date.)
    :status :running
    :metadata (:metadata opts {})}))
