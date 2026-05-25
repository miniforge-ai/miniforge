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

   Layer 0: pure helpers / constants
   Layer 1: queue I/O helpers (file read/write)
   Layer 2: public API (enqueue-workflow-gc!, run-deferred-gc!, gc-queue-path)"
  (:require
   [clojure.edn :as edn]
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]
   [ai.miniforge.dag-executor.result :as result])
  (:import
   [java.io File]
   [java.time Instant]))

;;------------------------------------------------------------------------------ Layer 0
;; Constants and pure helpers

(def ^:private gc-queue-filename
  "scratch-gc-queue.edn")

(def ^:private default-max-age-days
  "Default retention period in days before a finished workflow's scratch ref
   is eligible for garbage collection."
  7)

(defn gc-queue-path
  "Return the absolute path string for `~/.miniforge/scratch-gc-queue.edn`.

   Creates `~/.miniforge/` when it does not exist.  Safe to call on every
   workflow start — the `mkdirs` is a no-op when the directory exists."
  []
  (let [home (System/getProperty "user.home")
        dir  (File. ^String home ".miniforge")]
    (.mkdirs dir)
    (.getAbsolutePath (File. dir ^String gc-queue-filename))))

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
   Entries are serialised with `pr-str` — callers must ensure they contain
   only EDN-printable values (strings, `#inst` dates, keywords, numbers)."
  [path entries]
  (spit path (pr-str (vec entries))))

(defn- epoch-seconds
  "Current time as seconds since the Unix epoch."
  []
  (quot (System/currentTimeMillis) 1000))

(defn- finished-at-epoch-seconds
  "Extract the `:finished-at` value from a queue entry as epoch seconds.
   Supports `java.util.Date` instances (stored as `#inst` tagged literals).
   Returns 0 when the field is absent or unparseable — such entries are
   treated as freshly added and will never qualify as stale."
  [entry]
  (try
    (let [fa (:finished-at entry)]
      (cond
        ;; #inst tagged literals are read back as java.util.Date by Clojure's
        ;; EDN reader, which implements java.util.Date and satisfies inst?.
        (inst? fa) (quot (.getTime ^java.util.Date fa) 1000)
        ;; Belt-and-suspenders: handle raw Instant if somehow stored that way.
        (instance? Instant fa) (.getEpochSecond ^Instant fa)
        :else 0))
    (catch Exception _ 0)))

;;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn enqueue-workflow-gc!
  "Append a GC entry for `workflow-id` to `~/.miniforge/scratch-gc-queue.edn`.

   Writes `{:workflow-id <str> :finished-at #inst <now>}` at the end of the
   queue vector.  The file is created when absent.

   Intended to be called at workflow completion (success or failure) so the
   corresponding scratch ref becomes eligible for GC after `default-max-age-days`.

   Returns:
     result/ok  `{:workflow-id str :queue-size int}`
     result/err `:scratch-gc-queue/enqueue-failed` on any I/O failure"
  [workflow-id]
  (try
    (let [path    (gc-queue-path)
          entries (read-gc-queue path)
          ;; java.util.Date is serialised as #inst by pr-str and round-trips
          ;; through edn/read-string — do not substitute Instant here.
          entry   {:workflow-id (str workflow-id)
                   :finished-at (java.util.Date/from (Instant/now))}
          updated (conj entries entry)]
      (write-gc-queue! path updated)
      (result/ok {:workflow-id (str workflow-id)
                  :queue-size  (count updated)}))
    (catch Exception e
      (result/err :scratch-gc-queue/enqueue-failed
                  (str "failed to enqueue workflow GC entry: " (.getMessage e))
                  {:workflow-id (str workflow-id)}))))

(defn run-deferred-gc!
  "Scan the GC queue and delete stale scratch refs if any entries are overdue.

   Steps:
   1. Read `~/.miniforge/scratch-gc-queue.edn`.
   2. Partition entries into stale (age ≥ max-age-days) and fresh.
   3. If stale entries exist, call `gc-scratch-refs!` once on `parent-repo-path`
      with `max-age-days` — this deletes *all* refs in that repo older than the
      threshold, not just the stale queue entries.
   4. Write only the fresh entries back to the queue file.

   When there are no stale entries the queue file is left unchanged and the
   git plumbing is not invoked (zero I/O penalty for young workflows).

   Arguments:
   - `parent-repo-path`  Absolute path to the parent git repository whose
                         `refs/miniforge/scratch/` namespace is cleaned.
   - `max-age-days`      Retention period; entries older than this trigger GC.
                         Defaults to [[default-max-age-days]] (7 days).

   Returns:
     result/ok  `{:pruned int :remaining int :gc-result map-or-nil}`
     result/err `:scratch-gc-queue/run-failed` on unexpected failure"
  ([parent-repo-path]
   (run-deferred-gc! parent-repo-path default-max-age-days))
  ([parent-repo-path max-age-days]
   (try
     (let [path      (gc-queue-path)
           entries   (read-gc-queue path)
           max-age-s (* (long max-age-days) 86400)
           now-s     (epoch-seconds)
           stale?    (fn [e] (>= (- now-s (finished-at-epoch-seconds e)) max-age-s))
           {stale true fresh false} (group-by stale? entries)]
       (if (seq stale)
         (let [gc-result (scratch-commit/gc-scratch-refs! parent-repo-path max-age-days)]
           (write-gc-queue! path (or fresh []))
           (if (result/ok? gc-result)
             (result/ok {:pruned    (count stale)
                         :remaining (count (or fresh []))
                         :gc-result (get gc-result :data)})
             ;; Propagate git-level GC failure as-is; caller decides whether to swallow.
             gc-result))
         (result/ok {:pruned    0
                     :remaining (count entries)
                     :gc-result nil})))
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
