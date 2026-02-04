(ns ai.miniforge.dag-executor.parallel
  "Concurrency control and resource locking for DAG execution.

   Layer 0: Resource lock types and constructors
   Layer 1: Lock acquisition and release
   Layer 2: Parallel dispatch coordination"
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.logging.interface :as log]
   [clojure.set :as set])
  (:import
   [java.util.concurrent Semaphore TimeUnit]))

;------------------------------------------------------------------------------ Layer 0
;; Resource types and lock pool

(def resource-types
  "Types of resources that can be locked."
  #{:repo-write          ; Exclusive write access to repository
    :exclusive-files     ; Specific file paths (requires file list)
    :worktree})          ; Git worktree for isolation

(defn create-lock-pool
  "Create a lock pool for managing concurrent resource access.

   Options:
   - :max-repo-writes - Max concurrent repo writes (default 1 for safety)
   - :max-worktrees - Max concurrent worktrees (default 4)"
  [& {:keys [max-repo-writes max-worktrees]
      :or {max-repo-writes 1 max-worktrees 4}}]
  (atom {:locks {}
         :file-locks {}
         :semaphores {:repo-write (Semaphore. max-repo-writes true)
                      :worktree (Semaphore. max-worktrees true)}
         :config {:max-repo-writes max-repo-writes
                  :max-worktrees max-worktrees}}))

;------------------------------------------------------------------------------ Layer 0
;; Lock record constructors

(defn create-lock
  "Create a lock record."
  [resource-type holder-id & {:keys [files]}]
  {:lock/id (random-uuid)
   :lock/resource-type resource-type
   :lock/holder-id holder-id
   :lock/files (when (= resource-type :exclusive-files) (set files))
   :lock/acquired-at (java.util.Date.)})

;------------------------------------------------------------------------------ Layer 1
;; Lock acquisition (semaphore-based)

(defn- try-acquire-semaphore
  "Try to acquire a semaphore with timeout.
   Returns true if acquired, false if timeout."
  [^Semaphore sem timeout-ms]
  (.tryAcquire sem timeout-ms TimeUnit/MILLISECONDS))

(defn- release-semaphore
  "Release a semaphore permit."
  [^Semaphore sem]
  (.release sem))

(defn acquire-repo-write!
  "Acquire exclusive repo write lock.
   Blocks until lock is available or timeout.

   Returns result with lock or error."
  [lock-pool holder-id timeout-ms logger]
  (let [sem (get-in @lock-pool [:semaphores :repo-write])]
    (when logger
      (log/debug logger :dag-executor :lock/acquiring
                 {:message "Acquiring repo write lock"
                  :data {:holder-id holder-id}}))
    (if (try-acquire-semaphore sem timeout-ms)
      (let [lock (create-lock :repo-write holder-id)]
        (swap! lock-pool assoc-in [:locks holder-id] lock)
        (when logger
          (log/info logger :dag-executor :lock/acquired
                    {:message "Repo write lock acquired"
                     :data {:holder-id holder-id :lock-id (:lock/id lock)}}))
        (result/ok lock))
      (do
        (when logger
          (log/warn logger :dag-executor :lock/timeout
                    {:message "Timeout acquiring repo write lock"
                     :data {:holder-id holder-id :timeout-ms timeout-ms}}))
        (result/err :timeout
                    "Timeout acquiring repo write lock"
                    {:holder-id holder-id :timeout-ms timeout-ms})))))

(defn release-repo-write!
  "Release repo write lock."
  [lock-pool holder-id logger]
  (let [lock (get-in @lock-pool [:locks holder-id])
        sem (get-in @lock-pool [:semaphores :repo-write])]
    (if (and lock (= :repo-write (:lock/resource-type lock)))
      (do
        (release-semaphore sem)
        (swap! lock-pool update :locks dissoc holder-id)
        (when logger
          (log/info logger :dag-executor :lock/released
                    {:message "Repo write lock released"
                     :data {:holder-id holder-id}}))
        (result/ok {:released true}))
      (result/err :lock-not-found
                  "No repo write lock found for holder"
                  {:holder-id holder-id}))))

;------------------------------------------------------------------------------ Layer 1
;; File-based locking

(defn files-overlap?
  "Check if two sets of files have any overlap."
  [files-a files-b]
  (boolean (seq (set/intersection (set files-a) (set files-b)))))

(defn acquire-file-locks!
  "Acquire locks on specific files.
   Returns error if any files are already locked by another holder."
  [lock-pool holder-id files logger]
  (let [file-set (set files)
        current-locks (:file-locks @lock-pool)
        conflicts (->> current-locks
                       (filter (fn [[other-holder other-files]]
                                 (and (not= other-holder holder-id)
                                      (files-overlap? file-set other-files))))
                       (into {}))]
    (if (seq conflicts)
      (do
        (when logger
          (log/warn logger :dag-executor :lock/conflict
                    {:message "File lock conflict"
                     :data {:holder-id holder-id
                            :requested-files files
                            :conflicts conflicts}}))
        (result/err :lock-conflict
                    "Files already locked by another task"
                    {:holder-id holder-id
                     :conflicts conflicts}))
      (let [lock (create-lock :exclusive-files holder-id :files files)]
        (swap! lock-pool
               (fn [pool]
                 (-> pool
                     (assoc-in [:locks holder-id] lock)
                     (assoc-in [:file-locks holder-id] file-set))))
        (when logger
          (log/info logger :dag-executor :lock/acquired
                    {:message "File locks acquired"
                     :data {:holder-id holder-id :files files}}))
        (result/ok lock)))))

(defn release-file-locks!
  "Release file locks for a holder."
  [lock-pool holder-id logger]
  (let [lock (get-in @lock-pool [:locks holder-id])]
    (if (and lock (= :exclusive-files (:lock/resource-type lock)))
      (do
        (swap! lock-pool
               (fn [pool]
                 (-> pool
                     (update :locks dissoc holder-id)
                     (update :file-locks dissoc holder-id))))
        (when logger
          (log/info logger :dag-executor :lock/released
                    {:message "File locks released"
                     :data {:holder-id holder-id}}))
        (result/ok {:released true}))
      (result/err :lock-not-found
                  "No file locks found for holder"
                  {:holder-id holder-id}))))

;------------------------------------------------------------------------------ Layer 1
;; Worktree allocation

(defn acquire-worktree!
  "Acquire a worktree slot for isolated task execution.
   Blocks until slot is available or timeout."
  [lock-pool holder-id timeout-ms logger]
  (let [sem (get-in @lock-pool [:semaphores :worktree])]
    (when logger
      (log/debug logger :dag-executor :worktree/acquiring
                 {:message "Acquiring worktree slot"
                  :data {:holder-id holder-id}}))
    (if (try-acquire-semaphore sem timeout-ms)
      (let [lock (create-lock :worktree holder-id)]
        (swap! lock-pool assoc-in [:locks holder-id :worktree] lock)
        (when logger
          (log/info logger :dag-executor :worktree/acquired
                    {:message "Worktree slot acquired"
                     :data {:holder-id holder-id}}))
        (result/ok lock))
      (do
        (when logger
          (log/warn logger :dag-executor :worktree/timeout
                    {:message "Timeout acquiring worktree slot"
                     :data {:holder-id holder-id :timeout-ms timeout-ms}}))
        (result/err :timeout
                    "Timeout acquiring worktree slot"
                    {:holder-id holder-id :timeout-ms timeout-ms})))))

(defn release-worktree!
  "Release a worktree slot."
  [lock-pool holder-id logger]
  (let [lock (get-in @lock-pool [:locks holder-id :worktree])
        sem (get-in @lock-pool [:semaphores :worktree])]
    (if lock
      (do
        (release-semaphore sem)
        (swap! lock-pool update-in [:locks holder-id] dissoc :worktree)
        (when logger
          (log/info logger :dag-executor :worktree/released
                    {:message "Worktree slot released"
                     :data {:holder-id holder-id}}))
        (result/ok {:released true}))
      (result/err :lock-not-found
                  "No worktree lock found for holder"
                  {:holder-id holder-id}))))

;------------------------------------------------------------------------------ Layer 2
;; Parallel dispatch coordination

(defn can-run-parallel?
  "Check if two tasks can run in parallel.
   Tasks can run in parallel if:
   - They have no dependency relationship
   - They don't have overlapping file locks
   - They don't both require exclusive repo write"
  [task-a task-b lock-pool]
  (let [locks-a (get-in @lock-pool [:file-locks (:task/id task-a)] #{})
        locks-b (get-in @lock-pool [:file-locks (:task/id task-b)] #{})
        files-overlap? (seq (set/intersection locks-a locks-b))
        both-need-repo-write? (and (:needs-repo-write? task-a)
                                   (:needs-repo-write? task-b))]
    (not (or files-overlap? both-need-repo-write?))))

(defn select-parallel-batch
  "Select a batch of tasks that can run in parallel.
   Given ready tasks, returns subset that don't conflict with each other."
  [ready-task-ids run-state _lock-pool max-parallel]
  (let [tasks (select-keys (:run/tasks run-state) ready-task-ids)]
    (loop [remaining (seq tasks)
           selected []
           selected-files #{}]
      (cond
        (nil? remaining)
        selected

        (>= (count selected) max-parallel)
        selected

        :else
        (let [[task-id task] (first remaining)
              task-files (get-in task [:task/config :exclusive-files] #{})
              conflicts-with-selected? (seq (set/intersection
                                             selected-files
                                             (set task-files)))]
          (if conflicts-with-selected?
            (recur (rest remaining) selected selected-files)
            (recur (rest remaining)
                   (conj selected task-id)
                   (into selected-files task-files))))))))

(defn available-capacity
  "Get available capacity for parallel execution."
  [lock-pool]
  (let [repo-sem (get-in @lock-pool [:semaphores :repo-write])
        worktree-sem (get-in @lock-pool [:semaphores :worktree])]
    {:repo-write-available (.availablePermits ^Semaphore repo-sem)
     :worktree-available (.availablePermits ^Semaphore worktree-sem)
     :current-file-locks (count (:file-locks @lock-pool))}))

(defn release-all-locks!
  "Release all locks held by a task (cleanup on completion/failure)."
  [lock-pool holder-id logger]
  (let [locks (get-in @lock-pool [:locks holder-id])
        repo-sem (get-in @lock-pool [:semaphores :repo-write])
        worktree-sem (get-in @lock-pool [:semaphores :worktree])]
    ;; Release repo write if held
    (when (and (:lock/resource-type locks)
               (= :repo-write (:lock/resource-type locks)))
      (release-semaphore repo-sem))
    ;; Release worktree if held
    (when (:worktree locks)
      (release-semaphore worktree-sem))
    ;; Clear all lock state
    (swap! lock-pool
           (fn [pool]
             (-> pool
                 (update :locks dissoc holder-id)
                 (update :file-locks dissoc holder-id))))
    (when logger
      (log/info logger :dag-executor :lock/all-released
                {:message "All locks released for task"
                 :data {:holder-id holder-id}}))
    (result/ok {:released-all true})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create lock pool
  (def pool (create-lock-pool :max-repo-writes 1 :max-worktrees 4))

  ;; Check capacity
  (available-capacity pool)
  ; => {:repo-write-available 1 :worktree-available 4 :current-file-locks 0}

  ;; Acquire repo write lock
  (def task-id (random-uuid))
  (def lock-result (acquire-repo-write! pool task-id 5000 nil))
  (result/ok? lock-result)  ; => true

  ;; Second task should block/timeout
  (def task-2-id (random-uuid))
  (def lock-result-2 (acquire-repo-write! pool task-2-id 100 nil))
  (result/err? lock-result-2)  ; => true (timeout)

  ;; Release first lock
  (release-repo-write! pool task-id nil)
  (available-capacity pool)
  ; => {:repo-write-available 1 ...}

  ;; File locks
  (def files-result (acquire-file-locks! pool task-id ["src/foo.clj" "src/bar.clj"] nil))
  (result/ok? files-result)  ; => true

  ;; Conflicting file lock should fail
  (def conflict-result (acquire-file-locks! pool task-2-id ["src/foo.clj"] nil))
  (result/err? conflict-result)  ; => true

  ;; Non-conflicting should succeed
  (def no-conflict (acquire-file-locks! pool task-2-id ["src/baz.clj"] nil))
  (result/ok? no-conflict)  ; => true

  ;; Cleanup
  (release-all-locks! pool task-id nil)
  (release-all-locks! pool task-2-id nil)

  :leave-this-here)
