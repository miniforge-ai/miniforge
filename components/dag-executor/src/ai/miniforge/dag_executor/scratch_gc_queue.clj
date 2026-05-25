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
   `enqueue-workflow-gc!` appends `{:workflow-id wid :finished-at #inst <now>}`
   to `~/.miniforge/scratch-gc-queue.edn`.  The file is an EDN vector that
   survives process restarts.

   **Phase 2 — GC pass on next start (or explicit subcommand)**
   `run-deferred-gc!` reads the queue, partitions entries into stale (≥ max-age-days
   old) and fresh.  If any stale entries exist it calls `gc-scratch-refs!` once on
   the supplied parent-repo-path with `max-age-days`, then writes only the fresh
   entries back.  No background daemon needed.

   **Concurrency safety**
   Both mutating operations (`enqueue-workflow-gc!` and `run-deferred-gc!`) hold
   an exclusive JVM advisory `FileLock` across the read→modify→write window.
   Concurrent processes poll for up to 500 ms before giving up with a
   `:scratch-gc-queue/locked` error — acceptable for the best-effort GC contract.

   Layer 0: pure helpers / constants
   Layer 1: queue I/O helpers (file read/write, locking)
   Layer 2: public API (enqueue-workflow-gc!, run-deferred-gc!, gc-queue-path)"
  (:require
   [clojure.edn :as edn]
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]
   [ai.miniforge.dag-executor.result :as result])
  (:import
   [java.io File]
   [java.nio.channels FileChannel FileLock]
   [java.nio.file Paths StandardOpenOption]
   [java.time Instant]))

;;------------------------------------------------------------------------------ Layer 0
;; Constants and pure helpers

(def ^:private gc-queue-filename
  "scratch-gc-queue.edn")

(def default-max-age-days
  "Default retention period in days before a finished workflow's scratch ref
   is eligible for garbage collection.  Exported so CLI commands can reference
   the canonical value instead of duplicating the magic number `7`."
  7)

(def ^:private seconds-per-day
  "Seconds in one calendar day.  Named constant to make age computations
   self-documenting and to avoid duplicating the magic number `86400`."
  86400)

;; Lazily compute and cache the path on first call so `.mkdirs` is invoked
;; at most once per JVM lifetime rather than on every enqueue/GC call.
(def ^:private miniforge-queue-path
  "Delay that computes and caches `~/.miniforge/scratch-gc-queue.edn`.
   Side-effect: creates the `~/.miniforge/` directory on first deref."
  (delay
   (let [home (System/getProperty "user.home")
         dir  (File. ^String home ".miniforge")]
     (.mkdirs dir)
     (.getAbsolutePath (File. dir ^String gc-queue-filename)))))

(defn gc-queue-path
  "Return the absolute path string for `~/.miniforge/scratch-gc-queue.edn`.

   Creates `~/.miniforge/` on the first call; subsequent calls return the
   cached path without any filesystem syscall."
  []
  @miniforge-queue-path)

;;------------------------------------------------------------------------------ Layer 1
;; Queue I/O helpers

(defn- read-gc-queue
  "Read all entries from `path`.  Returns a (possibly empty) vector.
   Returns `[]` when the file is absent, empty, or unparseable — callers
   must treat the result as read-only and not mutate it in-place."
  [path]
  (try
    (let [f (File. ^String path)]
      (if (.exists f)
        (let [content (slurp path)
              parsed  (edn/read-string content)]
          (if (sequential? parsed) (vec parsed) []))
        []))
    (catch Exception _ [])))

(defn- write-gc-queue!
  "Overwrite `path` with the EDN representation of `entries`.

   Accepted entry value types: strings, keywords, numbers, `java.util.Date`
   (serialised as `#inst` literals by `pr-str`), and nested maps/vectors of
   the above.  Avoid `java.nio.file.Path`, `java.io.File`, or any type not
   readable by `clojure.edn/read-string` — `pr-str` will emit
   non-round-trippable output for those types without raising an error, which
   will cause the next queue read to return `[]` and silently drop all entries."
  [path entries]
  (spit path (pr-str (vec entries))))

(defn- epoch-seconds
  "Current time as seconds since the Unix epoch."
  []
  (quot (System/currentTimeMillis) 1000))

(defn- finished-at-epoch-seconds
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
      (cond
        ;; #inst tagged literals are read back as java.util.Date by Clojure's
        ;; EDN reader, which implements java.util.Date and satisfies inst?.
        (inst? fa) (quot (.getTime ^java.util.Date fa) 1000)
        ;; Belt-and-suspenders: handle raw Instant if somehow stored that way.
        (instance? Instant fa) (.getEpochSecond ^Instant fa)
        ;; Missing or unrecognised type — treat as never-stale to be safe.
        :else Long/MAX_VALUE))
    (catch Exception _ Long/MAX_VALUE)))

(defn- with-queue-lock!
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
        (let [deadline (+ (System/currentTimeMillis) 500)]
          (loop []
            (if-let [^FileLock lk (.tryLock ch)]
              (try (thunk) (finally (.release lk)))
              (if (< (System/currentTimeMillis) deadline)
                (do (Thread/sleep 25) (recur))
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
        (result/err :scratch-gc-queue/lock-failed
                    (str "failed to acquire queue file lock: " (.getMessage e))
                    {:path path})))))

;;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn enqueue-workflow-gc!
  "Append a GC entry for `workflow-id` to `~/.miniforge/scratch-gc-queue.edn`.

   Writes `{:workflow-id <str> :finished-at #inst <now>}` at the end of the
   queue vector.  The file is created when absent.

   Intended to be called at workflow completion (success or failure) so the
   corresponding scratch ref becomes eligible for GC after `default-max-age-days`.

   The read→modify→write is protected by an exclusive `FileLock` to prevent
   concurrent CLI processes from overwriting each other's queue entries.

   Returns:
     result/ok  `{:workflow-id str :queue-size int}`
     result/err `:scratch-gc-queue/enqueue-failed` on I/O failure
     result/err `:scratch-gc-queue/locked` when the lock is contended"
  [workflow-id]
  (try
    (let [path (gc-queue-path)]
      (with-queue-lock! path
        (fn []
          (let [entries (read-gc-queue path)
                ;; java.util.Date is serialised as #inst by pr-str and round-trips
                ;; through edn/read-string — do not substitute Instant here.
                entry   {:workflow-id (str workflow-id)
                         :finished-at (java.util.Date/from (Instant/now))}
                updated (conj entries entry)]
            (write-gc-queue! path updated)
            (result/ok {:workflow-id (str workflow-id)
                        :queue-size  (count updated)})))))
    (catch Exception e
      (result/err :scratch-gc-queue/enqueue-failed
                  (str "failed to enqueue workflow GC entry: " (.getMessage e))
                  {:workflow-id (str workflow-id)}))))

(defn run-deferred-gc!
  "Scan the GC queue and delete stale scratch refs if any entries are overdue.

   Steps:
   1. Acquire an exclusive file lock on `~/.miniforge/scratch-gc-queue.edn`.
   2. Read the queue and partition entries into stale (age ≥ max-age-days) and fresh.
   3. If stale entries exist, call `gc-scratch-refs!` once on `parent-repo-path`
      with `max-age-days` — this deletes *all* refs in that repo older than the
      threshold, not just the stale queue entries.
   4. **Only on success** write the fresh entries back; on git-level failure the
      queue is left unchanged so stale entries are retried on the next call.
   5. Release the lock.

   When there are no stale entries the queue file is left unchanged and the
   git plumbing is not invoked (zero I/O penalty for young workflows).

   Arguments:
   - `parent-repo-path`  Absolute path to the parent git repository whose
                         `refs/miniforge/scratch/` namespace is cleaned.
   - `max-age-days`      Retention period; entries older than this trigger GC.
                         Defaults to [[default-max-age-days]] (7 days).

   Returns:
     result/ok  `{:pruned int :remaining int :gc-result map-or-nil}`
     result/err `:scratch-gc-queue/run-failed` on unexpected failure
     result/err `:scratch-gc-queue/locked` when the lock is contended"
  ([parent-repo-path]
   (run-deferred-gc! parent-repo-path default-max-age-days))
  ([parent-repo-path max-age-days]
   (try
     (let [path (gc-queue-path)]
       (with-queue-lock! path
         (fn []
           (let [entries   (read-gc-queue path)
                 max-age-s (* (long max-age-days) seconds-per-day)
                 now-s     (epoch-seconds)
                 stale?    (fn [e] (>= (- now-s (finished-at-epoch-seconds e)) max-age-s))
                 {stale true fresh false} (group-by stale? entries)]
             (if (seq stale)
               (let [gc-result (scratch-commit/gc-scratch-refs! parent-repo-path max-age-days)]
                 (if (result/ok? gc-result)
                   ;; GC succeeded: write back only fresh entries.
                   (do
                     (write-gc-queue! path (or fresh []))
                     (result/ok {:pruned     (count stale)
                                 :pruned-ids (mapv :workflow-id stale)
                                 :remaining  (count (or fresh []))
                                 :gc-result  (get gc-result :data)}))
                   ;; GC failed: leave queue untouched; propagate the error.
                   gc-result))
               (result/ok {:pruned     0
                           :pruned-ids []
                           :remaining  (count entries)
                           :gc-result  nil}))))))
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
  ;=> {:status :ok :data {:workflow-id "task-a5a93af5" :queue-size 1}}

  ;; Run GC piggybacking on next workflow start.
  ;; Deletes scratch refs older than 7 days; writes fresh entries back to queue.
  (run-deferred-gc! "/path/to/parent-repo")
  ;=> {:status :ok :data {:pruned 0 :remaining 1 :gc-result nil}}

  ;; Immediate GC — wipe all scratch refs now.
  (run-deferred-gc! "/path/to/parent-repo" 0)
  ;=> {:status :ok :data {:pruned 1 :remaining 0 :gc-result {:deleted [...] :retained 0}}}

  :leave-this-here)
