;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-store-gc
  "Retention for durable checkpoints, and the hold that keeps a run's
   events as long as its checkpoint.

   `gc-checkpoints!` deletes a run's checkpoint directory once nothing in
   it has changed for the retention period (30 days by default, per the
   checkpoint spec), unless the run is pinned (`:pinned? true` in its
   manifest), its manifest cannot be read (so whether it is pinned is
   unknown), or its events are still live (in flight or paused).

   Event retention is shorter (archived events go 7 days after archiving),
   so without a hold a failed run resumed after a week would find its
   checkpoint and no events. The `retention-hold` method below keeps a
   run's archived events while its checkpoint exists; once the GC deletes
   the checkpoint, the next event cleanup prunes them."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.workflow.checkpoint-store :as checkpoint-store]
   [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
   [ai.miniforge.workflow.messages :as messages]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-retention-days
  "Days a checkpoint is kept after its last change (the checkpoint spec's
   GROUP 6 default)."
  30)

(def ^{:stratum 0} ^:private day-ms (* 24 60 60 1000))

(defmethod ^{:stratum 0} es/retention-hold ::checkpoint
  [_hold workflow-id]
  (checkpoint-store/checkpoint-present? workflow-id))

(defn- ^{:stratum 0} run-id
  "The run id a checkpoint directory is named for, nil for anything that
   is not a run directory: the GC never touches what it did not write."
  [dir]
  (parse-uuid (str (fs/file-name dir))))

(defn- ^{:stratum 0} last-change-ms
  "When anything in `dir` last changed: the newest mtime in its tree."
  [dir]
  (transduce (map #(.lastModified ^java.io.File %)) max 0 (file-seq (fs/file dir))))

(defn- ^{:stratum 0} manifest-hold
  "`:pinned` when the run's manifest pins it, `:unreadable` when the
   manifest exists but cannot be read as a map (pinned or not is then
   unknown), else nil."
  [dir]
  (let [manifest (fs/file dir checkpoint-paths/manifest-filename)]
    (when (fs/exists? manifest)
      (try+
        (let [content (edn/read-string (slurp manifest))]
          (cond (not (map? content)) :unreadable
                (true? (:pinned? content)) :pinned))
        (catch Exception _ :unreadable)))))

(defn- ^{:stratum 0} delete-run!
  "Delete one run's checkpoint directory: its id, or an anomaly naming it."
  [dir id]
  (try+
    (fs/delete-tree dir)
    id
    (catch Exception e
      (anomaly/exception-anomaly :unavailable
                                 (messages/t :checkpoint/gc-delete-failed
                                             {:path (str dir)
                                              :error (or (ex-message e) (.getName (class e)))})
                                 {:workflow/run-id id :checkpoint/path (str dir)}
                                 e))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} disposition
  "What the GC does with run directory `dir`: `:recent`, `:live`,
   `:pinned`, `:unreadable` (all kept) or `:expired` (deleted); nil when
   `dir` is not a run directory."
  [dir cutoff-ms events-dir]
  (when-let [id (run-id dir)]
    (cond
      (>= (last-change-ms dir) cutoff-ms) :recent
      (fs/directory? (es/workflow-dir events-dir id)) :live
      :else (or (manifest-hold dir) :expired))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} gc-checkpoints!
  "Delete expired checkpoint directories under the checkpoint root.

   Options:
   - `:checkpoint/root` — root to collect (default: the configured one)
   - `:max-age-days`    — retention after the last change (default 30)
   - `:events-dir`      — events root whose live/ runs are kept
                          (default ~/.miniforge/events)
   - `:now-ms`          — clock, for tests

   Returns `{:checkpoint/root :deleted [run-id] :kept {disposition [run-id]}
   :failures [anomaly]}`. Only directories named by a run id are
   considered; a symlinked one is never followed."
  [opts]
  (let [root (checkpoint-paths/resolve-checkpoint-root opts)
        days (get opts :max-age-days default-retention-days)
        cutoff-ms (- (get opts :now-ms (System/currentTimeMillis)) (* days day-ms))
        events-dir (get opts :events-dir (es/default-events-dir))
        runs (when (fs/directory? root)
               (filter #(fs/directory? % {:nofollow-links true}) (fs/list-dir root)))
        by-disposition (group-by #(disposition % cutoff-ms events-dir) runs)
        results (mapv #(delete-run! % (run-id %)) (get by-disposition :expired))]
    {:checkpoint/root root
     :deleted (filterv uuid? results)
     :kept (into {}
                 (map (fn [[k dirs]] [k (mapv run-id dirs)]))
                 (dissoc by-disposition :expired nil))
     :failures (filterv anomaly/anomaly? results)}))

(comment
  (gc-checkpoints! {:max-age-days 30}))
