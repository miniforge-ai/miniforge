(ns ai.miniforge.dag-executor.protocols.impl.worktree
  "Worktree executor implementation (fallback).

   Provides task isolation via git worktrees. This is the fallback executor
   when no container runtime is available. Isolation is limited to filesystem
   separation - processes run on the host."
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [clojure.java.shell :as shell]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.nio.file Files StandardCopyOption CopyOption]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-base-path "/tmp/miniforge-worktrees")
(def default-max-concurrent 4)

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- run-git
  "Execute a git command and return the result."
  [& args]
  (try
    (apply shell/sh "git" args)
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn- run-shell
  "Execute a shell command and return the result."
  [& args]
  (try
    (apply shell/sh args)
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn- ensure-directory
  "Ensure a directory exists."
  [path]
  (.mkdirs (File. ^String path)))

;; ============================================================================
;; Git Operations
;; ============================================================================

(defn git-version
  "Get git version."
  []
  (let [result (run-git "--version")]
    (if (zero? (:exit result))
      {:available? true
       :git-version (str/trim (:out result))}
      {:available? false
       :reason "git not found"})))

(defn create-worktree
  "Create a git worktree for the task.

   First tries to create a new branch, then falls back to using existing branch."
  [base-path repo-path worktree-name branch]
  (let [worktree-path (str base-path "/" worktree-name)]
    (ensure-directory base-path)
    ;; Try creating with a new branch first
    (let [result (run-git "-C" repo-path
                          "worktree" "add" "-b" worktree-name
                          worktree-path branch)]
      (if (zero? (:exit result))
        (result/ok {:worktree-path worktree-path})
        ;; Branch might exist, try without -b
        (let [result2 (run-git "-C" repo-path
                               "worktree" "add" worktree-path branch)]
          (if (zero? (:exit result2))
            (result/ok {:worktree-path worktree-path})
            (result/err :worktree-create-failed (:err result2))))))))

(defn remove-worktree
  "Remove a git worktree."
  [worktree-path]
  ;; Try git worktree remove first
  (let [result (run-git "-C" worktree-path
                        "worktree" "remove" "--force" worktree-path)]
    (if (zero? (:exit result))
      (result/ok {:released? true})
      ;; Fallback: just delete the directory
      (do
        (run-shell "rm" "-rf" worktree-path)
        (result/ok {:released? true})))))

;; ============================================================================
;; Command Execution
;; ============================================================================

(defn execute-command
  "Execute a command in a directory using ProcessBuilder.

   This allows proper environment variable handling and working directory."
  [workdir command env-map]
  (let [cmd-str (if (string? command)
                  command
                  (str/join " " command))
        start-time (System/currentTimeMillis)]
    (try
      (let [pb (ProcessBuilder. ["sh" "-c" cmd-str])
            _ (.directory pb (File. ^String workdir))
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

;; ============================================================================
;; File Operations
;; ============================================================================

(defn copy-file-to
  "Copy a file into the worktree."
  [worktree-path local-path remote-path]
  (try
    (let [source (File. ^String local-path)
          dest-path (str worktree-path "/" remote-path)
          dest (File. ^String dest-path)]
      (.mkdirs (.getParentFile dest))
      (Files/copy (.toPath source)
                  (.toPath dest)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (result/ok {:copied-bytes (.length source)}))
    (catch Exception e
      (result/err :copy-failed (.getMessage e)))))

(defn copy-file-from
  "Copy a file from the worktree."
  [worktree-path remote-path local-path]
  (try
    (let [source-path (str worktree-path "/" remote-path)
          source (File. ^String source-path)
          dest (File. ^String local-path)]
      (.mkdirs (.getParentFile dest))
      (Files/copy (.toPath source)
                  (.toPath dest)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (result/ok {:copied-bytes (.length source)}))
    (catch Exception e
      (result/err :copy-failed (.getMessage e)))))

;; ============================================================================
;; Public Utility Functions
;; ============================================================================

(defn create-worktree!
  "Create a git worktree at <base-path>/task-<task-id> from <branch>.

   Takes a map with keys:
   - :base-path  - Base directory under which the worktree directory is created
   - :repo-path  - Path to the git repository (used as -C arg)
   - :branch     - Branch/ref to check out in the worktree
   - :task-id    - Identifier for the task; the directory will be task-<task-id>

   Returns {:worktree-path string :branch string :task-id string} on success,
   or a result/err map on failure."
  [{:keys [base-path repo-path branch task-id]}]
  (let [worktree-name (str "task-" task-id)
        result (create-worktree base-path repo-path worktree-name branch)]
    (if (result/ok? result)
      (result/ok {:worktree-path (:worktree-path (:data result))
                  :branch branch
                  :task-id (str task-id)})
      result)))

(defn remove-worktree!
  "Remove a git worktree created by create-worktree!.

   Takes a map with keys:
   - :worktree-path - Absolute path to the worktree directory to remove"
  [{:keys [worktree-path]}]
  (remove-worktree worktree-path))

;; ============================================================================
;; WorktreeExecutor Record
;; ============================================================================

(defrecord WorktreeExecutor [config base-path max-concurrent]
  proto/TaskExecutor

  (executor-type [_this] :worktree)

  (available? [_this]
    (try
      (result/ok (git-version))
      (catch Exception e
        (result/ok {:available? false :reason (.getMessage e)}))))

  (acquire-environment! [_this task-id env-config]
    (let [worktree-name (str "task-" (subs (str task-id) 0 8))
          repo-path (or (:repo-path env-config) ".")
          branch (or (:branch env-config) "main")
          create-result (create-worktree base-path repo-path worktree-name branch)]
      (if (result/ok? create-result)
        (let [worktree-path (:worktree-path (:data create-result))]
          (result/ok (proto/create-environment-record
                      worktree-name :worktree task-id worktree-path
                      (assoc env-config
                             :metadata {:worktree-path worktree-path
                                        :repo-path repo-path}))))
        create-result)))

  (execute! [_this environment-id command opts]
    (let [worktree-path (str base-path "/" environment-id)
          workdir (or (:workdir opts) worktree-path)
          env-map (merge {} (:env opts))]
      (execute-command workdir command env-map)))

  (copy-to! [_this environment-id local-path remote-path]
    (let [worktree-path (str base-path "/" environment-id)]
      (copy-file-to worktree-path local-path remote-path)))

  (copy-from! [_this environment-id remote-path local-path]
    (let [worktree-path (str base-path "/" environment-id)]
      (copy-file-from worktree-path remote-path local-path)))

  (release-environment! [_this environment-id]
    (let [worktree-path (str base-path "/" environment-id)]
      (try
        (remove-worktree worktree-path)
        (catch Exception e
          (result/err :release-failed (.getMessage e))))))

  (environment-status [_this environment-id]
    (let [worktree-path (str base-path "/" environment-id)]
      (if (.exists (File. ^String worktree-path))
        (result/ok {:status :running})
        (result/ok {:status :stopped})))))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-worktree-executor
  "Create a worktree-based executor (fallback).

   Config:
   - :base-path - Base directory for worktrees (default: /tmp/miniforge-worktrees)
   - :max-concurrent - Max concurrent worktrees (default: 4)"
  [config]
  (map->WorktreeExecutor
   {:config config
    :base-path (or (:base-path config) default-base-path)
    :max-concurrent (or (:max-concurrent config) default-max-concurrent)}))
