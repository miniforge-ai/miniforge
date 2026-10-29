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
(ns ai.miniforge.dag-executor.scratch-gc-queue
  "Deferred garbage-collection queue for workflow scratch refs.

   ## Design

   Scratch refs accumulate under `refs/miniforge/scratch/<workflow-id>` while
   workflows run.  After a workflow finishes, its ref is no longer needed but
   we don't want to delete it immediately — the commit log is useful for 7 days
   for debugging crashed runs.  We also don't want a background daemon.

   Solution: a two-phase deferred GC driven by normal workflow traffic.

   **Phase 1 — enqueue on finish**
   `enqueue-workflow-gc!` appends
   `{:workflow-id wid :finished-at #inst <now> :git-dir <dir>}` to
   `<miniforge-home>/scratch-gc-queue.edn` (MINIFORGE_HOME, else ~/.miniforge).
   The file is an EDN vector that survives process restarts. `:git-dir` is
   the canonical git common dir of the repository that holds the ref (see
   `repo-key`), the same for every worktree of that repository.

   **Phase 2 — GC pass on next start (or explicit subcommand)**
   `run-deferred-gc!` reads the queue and looks only at entries whose
   `:git-dir` is the repository it was given, partitioning them into stale
   (≥ max-age-days old) and fresh.  If any are stale it calls
   `gc-scratch-refs!` once on that repository with `max-age-days`, then removes
   only those stale entries.  Entries of other repositories are kept as they
   are: removing them here would drop them without deleting their refs.
   Entries without `:git-dir` (written before it existed) name no repository.
   They stay queued, and a stale one also triggers the age-based sweep of
   the repository being collected, so an upgraded queue still gets GC.

   **Unreadable queue**
   A queue file that is not an EDN vector is never silently read as empty.
   Its bytes are copied to `<queue>.corrupt-<epoch-ms>`, the queue is reset to
   `[]`, and the call returns `:scratch-gc-queue/corrupt` naming the copy.

   **Concurrency safety**
   Both mutating operations (`enqueue-workflow-gc!` and `run-deferred-gc!`) hold
   an exclusive JVM advisory `FileLock` across the read→modify→write window.
   Concurrent processes poll for up to 500 ms before giving up with a
   `:scratch-gc-queue/locked` error — acceptable for the best-effort GC contract."
  (:require
   [ai.miniforge.config.interface :as mf-config]
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree-impl]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.nio.channels FileChannel]
   [java.nio.file Paths StandardOpenOption]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

;; Constants and pure helpers
(def ^{:stratum 0} ^:private gc-queue-filename
  "scratch-gc-queue.edn")

(def ^{:stratum 0} default-max-age-days
  "Default retention period in days before a finished workflow's scratch ref
   is eligible for garbage collection.  Exported so CLI commands can reference
   the canonical value instead of duplicating the magic number `7`."
  7)

(def ^{:stratum 0} ^:private seconds-per-day
  "Seconds in one calendar day.  Named constant to make age computations
   self-documenting and to avoid duplicating the magic number `86400`."
  86400)

(def ^{:stratum 0} ^:private lock-acquire-timeout-ms
  "Upper bound on how long `with-queue-lock!` waits for the advisory file
   lock before giving up with `:scratch-gc-queue/locked`.  Bounded because GC
   is best-effort — a contended queue should yield quickly, not block a CLI
   command or workflow start."
  500)

(def ^{:stratum 0} ^:private lock-poll-interval-ms
  "Backoff between `.tryLock` attempts while waiting for the queue lock.
   Small relative to [[lock-acquire-timeout-ms]] so contention resolves
   promptly without busy-spinning."
  25)

(defn ^{:stratum 0} repo-key
  "Identity of the repository that owns `path`: the canonical path of its
   git common dir. A main checkout, its linked worktrees and its .git dir
   share it; bare repositories, submodules and --separate-git-dir layouts
   each get their own git dir. Git commands run with it as their working
   directory, so it is also the GC target. result/ok string, or result/err
   `:scratch-gc-queue/no-repo`."
  [path]
  (let [r   (worktree-impl/run-git "-C" (str path) "rev-parse" "--git-common-dir")
        out (str/trim (str (:out r)))]
    (if (and (zero? (:exit r)) (seq out))
      ;; A relative answer is relative to `path`.
      (result/ok (.getCanonicalPath (let [f (io/file out)] (if (.isAbsolute f) f (io/file (str path) out)))))
      (result/err :scratch-gc-queue/no-repo
                  (str "not inside a git repository: " path)
                  {:path (str path)}))))

;; Queue I/O helpers
(defn- ^{:stratum 0} read-gc-queue
  "Entries of the queue at `path`: result/ok vector (`[]` when the file is
   absent or blank), or result/err `:scratch-gc-queue/corrupt` when the
   content is not an EDN vector. Never writes."
  [path]
  (let [f       (io/file path)
        content (if (.exists f) (slurp f) "")
        corrupt #(result/err :scratch-gc-queue/corrupt
                             (str "GC queue " path " is unreadable (" % ")")
                             {:path path})]
    (if (str/blank? content)
      (result/ok [])
      (try+
        (let [parsed (edn/read-string content)]
          (if (sequential? parsed)
            (result/ok (vec parsed))
            (corrupt (str "top-level form is " (pr-str (type parsed))))))
        (catch Exception e
          (corrupt (or (ex-message e) (str (type e)))))))))

(defn- ^{:stratum 0} write-gc-queue!
  "Overwrite `path` with the EDN representation of `entries`.

   Accepted entry value types: strings, keywords, numbers, `java.util.Date`
   (serialised as `#inst` literals by `pr-str`), and nested maps/vectors of
   the above.  Avoid `java.nio.file.Path`, `java.io.File`, or any type not
   readable by `clojure.edn/read-string` — `pr-str` would emit output the
   next read reports as a corrupt queue."
  [path entries]
  (spit path (pr-str (vec entries))))

(defn- ^{:stratum 0} epoch-seconds
  "Current time as seconds since the Unix epoch."
  []
  (quot (System/currentTimeMillis) 1000))

(defn- ^{:stratum 0} finished-at-epoch-seconds
  "Extract the `:finished-at` value from a queue entry as epoch seconds.
   Supports `java.util.Date` instances (stored as `#inst` tagged literals).

   Returns `Long/MAX_VALUE` when the field is absent or unparseable — such
   entries are treated as infinitely far in the future and will **never**
   qualify as stale.  This is the safe/defensive default that prevents
   accidentally GC-ing manually-crafted or migrated entries that lack a
   `:finished-at` timestamp.  Callers that explicitly want to prune
   malformed entries must do so via a separate code path."
  [entry]
  (try
    (let [fa (:finished-at entry)]
      (if (inst? fa)
        ;; #inst tagged literals are read back as java.util.Date by Clojure's
        ;; EDN reader, which implements java.util.Date and satisfies inst?.
        (quot (.getTime ^java.util.Date fa) 1000)
        ;; Missing or unrecognised type — treat as never-stale to be safe.
        Long/MAX_VALUE))
    (catch Exception _ Long/MAX_VALUE)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} gc-queue-path
  "Return the absolute path of `<miniforge-home>/scratch-gc-queue.edn`
   (MINIFORGE_HOME, else ~/.miniforge), creating that directory if absent."
  []
  (let [dir (io/file (mf-config/miniforge-home))]
    (.mkdirs dir)
    (.getAbsolutePath (io/file dir ^String gc-queue-filename))))

(defn- ^{:stratum 1} quarantine-corrupt-queue!
  "Copy the unreadable queue at `path` to `<path>.corrupt-<epoch-ms>`, reset
   `path` to an empty queue, and return `read-error` with the copy's path
   added. Copied, not renamed: the lock is held on the queue file itself, and
   a rename would let another process lock a fresh file meanwhile."
  [path read-error]
  (let [copy (str path ".corrupt-" (System/currentTimeMillis))]
    (io/copy (io/file path) (io/file copy))
    (write-gc-queue! path [])
    (-> read-error
        (update-in [:error :message] str "; its content was moved to " copy)
        (assoc-in [:error :data :quarantined] copy))))

(defn- ^{:stratum 1} with-queue-lock!
  "Acquire an exclusive advisory JVM file lock on `path`, execute `thunk`,
   then release the lock.

   Polls for up to 500 ms in 25 ms increments before giving up.  Returns
   `result/err :scratch-gc-queue/locked` when the lock cannot be acquired
   — this is the expected no-op for concurrent best-effort GC callers.

   The file is created at `path` if it does not already exist (needed so
   the FileChannel can be opened before the queue contains any entries)."
  [path thunk]
  (let [nio-path (Paths/get path (make-array String 0))
        opts     (into-array StandardOpenOption
                             [StandardOpenOption/READ
                              StandardOpenOption/WRITE
                              StandardOpenOption/CREATE])]
    (try
      (with-open [^FileChannel ch (FileChannel/open nio-path opts)]
        (let [deadline (+ (System/currentTimeMillis) lock-acquire-timeout-ms)]
          (loop []
            (if-let [^java.lang.AutoCloseable lk (.tryLock ch)]
              ;; FileLock implements AutoCloseable; .close releases the lock.
              ;; Hinting AutoCloseable (not FileLock) keeps the ns loadable
              ;; under babashka, which does not expose java.nio.channels.FileLock.
              (try (thunk) (finally (.close lk)))
              ;; Why Thread/sleep here is acceptable: an OS advisory file lock
              ;; has no blocking-with-timeout acquire API — `.tryLock` is
              ;; non-blocking and the blocking `.lock` waits forever (no
              ;; timeout), which we must avoid for a best-effort GC. So we
              ;; poll: brief sleep, retry, give up at the bounded deadline.
              ;; This runs on the calling CLI/workflow thread (no pool starved)
              ;; and the total wait is capped at lock-acquire-timeout-ms.
              (if (< (System/currentTimeMillis) deadline)
                (do (Thread/sleep lock-poll-interval-ms) (recur))
                (result/err :scratch-gc-queue/locked
                            "queue file is locked by another process"
                            {:path path}))))))
      (catch InterruptedException _
        ;; Restore the interrupt flag so callers using cooperative cancellation
        ;; (e.g. shutdown hooks, async frameworks) still see the signal.
        (.interrupt (Thread/currentThread))
        (result/err :scratch-gc-queue/lock-interrupted
                    "interrupted while waiting for queue lock"
                    {:path path}))
      (catch Exception e
        ;; Same-JVM overlap (OverlappingFileLockException) or a concurrently
        ;; closed channel (ClosedChannelException) means the lock is simply
        ;; unavailable — surface the expected contended :locked no-op, not a
        ;; hard :lock-failed. Matched by class name (not a class literal in a
        ;; catch clause) because this namespace loads under babashka, which
        ;; does not expose those java.nio.channels classes.
        (if (contains? #{"java.nio.channels.OverlappingFileLockException"
                         "java.nio.channels.ClosedChannelException"}
                       (.getName (class e)))
          (result/err :scratch-gc-queue/locked
                      "queue file lock unavailable (held in-JVM or channel closed)"
                      {:path path})
          (result/err :scratch-gc-queue/lock-failed
                      (str "failed to acquire queue file lock: " (.getMessage e))
                      {:path path}))))))

;------------------------------------------------------------------------------ Layer 2

;; Public API
(defn ^{:stratum 2} enqueue-workflow-gc!
  "Append a GC entry for `workflow-id` to the queue at `gc-queue-path`.

   Writes `{:workflow-id <str> :finished-at #inst <now> :git-dir <dir>}`
   at the end of the queue vector, `<dir>` being the `repo-key` of
   `repo-path` (default: the current directory).  The file is created when
   absent.

   Intended to be called at workflow completion (success or failure) so the
   corresponding scratch ref becomes eligible for GC after `default-max-age-days`.

   The read→modify→write is protected by an exclusive `FileLock` to prevent
   concurrent CLI processes from overwriting each other's queue entries.

   Returns:
     result/ok  `{:workflow-id str :queue-size int}`
     result/err `:scratch-gc-queue/no-repo` when `repo-path` is not in a git repository
     result/err `:scratch-gc-queue/corrupt` when the queue was unreadable (it is
                quarantined, nothing is enqueued, the next call starts empty)
     result/err `:scratch-gc-queue/enqueue-failed` on I/O failure during write
     result/err `:scratch-gc-queue/locked` when the lock is contended"
  ([workflow-id]
   (enqueue-workflow-gc! workflow-id (System/getProperty "user.dir")))
  ([workflow-id repo-path]
   (try
     (result/and-then
      (repo-key repo-path)
      (fn [repo]
        (let [path (gc-queue-path)]
          (with-queue-lock! path
            (fn []
              (let [queue (read-gc-queue path)]
                (if (result/err? queue)
                  (quarantine-corrupt-queue! path queue)
                  ;; java.util.Date is serialised as #inst by pr-str and round-trips
                  ;; through edn/read-string — do not substitute Instant here.
                  (let [entry   {:workflow-id (str workflow-id)
                                 :finished-at (java.util.Date/from (Instant/now))
                                 :git-dir     repo}
                        updated (conj (result/unwrap queue) entry)]
                    ;; Nested try so that spit failures surface as :enqueue-failed
                    ;; rather than being re-caught by with-queue-lock!'s outer handler
                    ;; (which would return the less-specific :lock-failed code).
                    (try
                      (write-gc-queue! path updated)
                      (result/ok {:workflow-id (str workflow-id)
                                  :queue-size  (count updated)})
                      (catch Exception e
                        (result/err :scratch-gc-queue/enqueue-failed
                                    (str "failed to write GC queue: " (.getMessage e))
                                    {:workflow-id (str workflow-id) :path path})))))))))))
     (catch Exception e
       (result/err :scratch-gc-queue/enqueue-failed
                   (str "failed to enqueue workflow GC entry: " (.getMessage e))
                   {:workflow-id (str workflow-id)})))))

(defn ^{:stratum 2} prune-stale-queue-entries!
  "Core of a deferred-GC pass, run while holding the queue lock.

   Reads the queue at `path` and takes the entries whose `:git-dir` is
   `repo` (a `repo-key`).  When any of them is stale (age ≥ `max-age-days`),
   or an entry without `:git-dir` is, it runs `gc-scratch-refs!` once on
   `repo`, then writes back every entry but this repository's stale ones.  On git-level GC failure the queue
   is left untouched (the error propagates) so stale entries are retried
   next pass.  An unreadable queue is quarantined and reported.

   Extracted from `run-deferred-gc!`'s lock thunk so the partition/write-back
   logic is independently testable.

   Returns:
     result/ok  `{:pruned int :pruned-ids [str ...] :remaining int :gc-result map-or-nil}`
     result/err the propagated `gc-scratch-refs!` error when GC fails
     result/err `:scratch-gc-queue/corrupt` when the queue was unreadable"
  [path repo max-age-days]
  (let [queue (read-gc-queue path)]
    (if (result/err? queue)
      (quarantine-corrupt-queue! path queue)
      (let [entries   (result/unwrap queue)
            max-age-s (* (long max-age-days) seconds-per-day)
            now-s     (epoch-seconds)
            old?      (fn [e] (>= (- now-s (finished-at-epoch-seconds e)) max-age-s))
            {stale true kept false} (group-by #(boolean (and (= repo (:git-dir %)) (old? %))) entries)]
        (if (or (seq stale) (some #(and (nil? (:git-dir %)) (old? %)) entries))
          (let [gc-result (scratch-commit/gc-scratch-refs! repo max-age-days)]
            (if (result/ok? gc-result)
              (do
                (write-gc-queue! path (or kept []))
                (result/ok {:pruned     (count stale)
                            :pruned-ids (mapv :workflow-id stale)
                            :remaining  (count kept)
                            :gc-result  (get gc-result :data)}))
              ;; GC failed: leave queue untouched; propagate the error.
              gc-result))
          (result/ok {:pruned     0
                      :pruned-ids []
                      :remaining  (count entries)
                      :gc-result  nil}))))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} run-deferred-gc!
  "Scan the GC queue and delete stale scratch refs of one repository.

   Steps:
   1. Resolve `parent-repo-path` to its `repo-key`.
   2. Acquire an exclusive file lock on the queue file.
   3. Take the entries of that repository and find the stale ones
      (age ≥ max-age-days).
   4. If any are stale, or an entry without `:git-dir` is, call
      `gc-scratch-refs!` once on the repository with
      `max-age-days` — this deletes *all* scratch refs in it older than the
      threshold, not just the stale queue entries.
   5. **Only on success** remove those stale entries; on git-level failure the
      queue is left unchanged so they are retried on the next call.  Entries of
      other repositories are never removed here.
   6. Release the lock.

   When there are no stale entries the queue file is left unchanged and the
   git plumbing is not invoked.

   Arguments:
   - `parent-repo-path`  A path inside the git repository whose
                         `refs/miniforge/scratch/` namespace is cleaned (any
                         of its worktrees will do).
   - `max-age-days`      Retention period; entries older than this trigger GC.
                         Defaults to [[default-max-age-days]] (7 days).

   Returns:
     result/ok  `{:pruned int :pruned-ids [str ...] :remaining int :gc-result map-or-nil}`
     result/err `:scratch-gc-queue/no-repo` when the path is not in a git repository
     result/err `:scratch-gc-queue/corrupt` when the queue was unreadable (quarantined)
     result/err `:scratch-gc-queue/run-failed` on unexpected failure
     result/err `:scratch-gc-queue/locked` when the lock is contended"
  ([parent-repo-path]
   (run-deferred-gc! parent-repo-path default-max-age-days))
  ([parent-repo-path max-age-days]
   (try
     (result/and-then
      (repo-key parent-repo-path)
      (fn [repo]
        (let [path (gc-queue-path)]
          (with-queue-lock! path
            (fn [] (prune-stale-queue-entries! path repo max-age-days))))))
     (catch Exception e
       (result/err :scratch-gc-queue/run-failed
                   (str "deferred GC pass failed: " (.getMessage e))
                   {:parent-repo-path (str parent-repo-path)
                    :max-age-days     max-age-days})))))

;;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Inspect the current queue without running GC.
  (gc-queue-path)
  ;=> "/Users/chris/.miniforge/scratch-gc-queue.edn"

  ;; Enqueue a workflow on finish (called by workflow_runner.clj).
  (enqueue-workflow-gc! "task-a5a93af5")
  ;=> {:ok? true :data {:workflow-id "task-a5a93af5" :queue-size 1}}

  ;; Run GC piggybacking on next workflow start.
  ;; Deletes scratch refs older than 7 days; removes this repo's stale entries.
  (run-deferred-gc! "/path/to/parent-repo")
  ;=> {:ok? true :data {:pruned 0 :pruned-ids [] :remaining 1 :gc-result nil}}

  ;; Immediate GC — wipe all scratch refs now.
  (run-deferred-gc! "/path/to/parent-repo" 0)
  ;=> {:ok? true :data {:pruned 1 :pruned-ids ["task-a5a93af5"] :remaining 0 :gc-result {:deleted [...] :retained 0}}}

  :leave-this-here)
