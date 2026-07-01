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

(ns ai.miniforge.event-stream.archive
  "BD-2b sub-3b: atomic workflow archive.

   When a workflow reaches a terminal status (`:completed`, `:failed`,
   `:cancelled`, or `:crashed`), the archive operation moves the
   workflow's directory from `live/{workflow-id}/` to
   `archived/{workflow-id}/`. The move is implemented as a crash-safe
   8-step sequence:

     1. Inside `live/{wid}`: write `snapshot.transit.json.tmp` if a
        live snapshot is queued (sub-3c will populate this; sub-3b
        tolerates the file's absence — `snapshot_status` stays
        `:none` in that case).
     2. fsync the snapshot tmp.
     3. Update `manifest.json`: `archive_status = :archiving` plus
        the watermark fields (`snapshot_watermark.last_event_id`,
        `workflow_sequence_number`). The manifest itself is
        atomic-written via `save-manifest!`.
     4. fsync manifest + parent dir.
     5. mv `snapshot.transit.json.tmp` → `snapshot.transit.json`.
     6. mv `live/{wid}` → `archived/{wid}` (atomic rename on same FS).
     7. Touch `archived/{wid}/archived.marker`.
     8. fsync `archived/{wid}` and its parent.

   Crash-recovery: any `live/{wid}` whose manifest has
   `archive_status == :archiving`, or any `archived/{wid}` missing
   `archived.marker`, is recovered by replaying steps 5–8.
   Idempotent — running recovery on an already-complete archive is
   a no-op.

   JVM-only (uses `java.nio.file.Files`, `RandomAccessFile.fd().sync`,
   and `ProcessHandle/of` indirectly via the manifest module)."
  {:miniforge/runtime :jvm-only}
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.manifest :as manifest]
   [ai.miniforge.event-stream.sinks :as sinks]
   [ai.miniforge.event-stream.storage-layout :as layout]
   [clojure.java.io :as io])
  (:import
   [java.io File RandomAccessFile]
   [java.nio.file Files StandardCopyOption]))

;; STRATIFIED-DESIGN LAYERING
;;
;;   Layer 0  atomic file primitives (fsync, atomic rename, marker)
;;   Layer 1  manifest-bound transitions (stage-archiving!,
;;            complete-snapshot-rename!, mark-archive-complete!)
;;   Layer 2  workflow-bound orchestration
;;            (archive-workflow!, recover-incomplete-archive!)
;;   Layer 3  fleet-level scan + recover
;;            (recover-all-incomplete!)

;------------------------------------------------------------------------------ Layer 0 — atomic file primitives

(defn- fsync-file!
  "Force-flush `file`'s data + metadata to disk. No-op when `file`
   doesn't exist (the snapshot-tmp step may legitimately skip when
   there's no snapshot yet — sub-3c fills it in)."
  [^File file]
  (when (.exists file)
    (with-open [raf (RandomAccessFile. file "rw")]
      (.force (.getChannel raf) true))))

(defn- fsync-dir!
  "Best-effort fsync on a directory. On macOS / Linux this commits
   the directory entries (renames, marker touches) to disk; on
   filesystems that don't support directory sync the call is a
   silent no-op."
  [^File dir]
  (when (.isDirectory dir)
    (try
      (with-open [raf (RandomAccessFile. dir "r")]
        (.force (.getChannel raf) true))
      (catch Exception _
        ;; Some filesystems / OSes refuse RandomAccessFile on a
        ;; directory. Treat as best-effort: we already fsynced the
        ;; files themselves; an unsupported parent-dir sync only
        ;; weakens the guarantee, doesn't break correctness on a
        ;; clean shutdown.
        nil))))

(defn- atomic-rename!
  "Atomic rename `src` → `dst` via `Files/move` with `ATOMIC_MOVE` +
   `REPLACE_EXISTING`. Requires same filesystem. Returns the dst
   `Path` on success."
  [^File src ^File dst]
  (Files/move (.toPath src)
              (.toPath dst)
              (into-array java.nio.file.CopyOption
                          [StandardCopyOption/ATOMIC_MOVE
                           StandardCopyOption/REPLACE_EXISTING])))

(defn- touch-marker!
  "Create an empty marker file at `path`. Used for
   `archived.marker` so a partial archive (missing the marker) is
   identifiable on next boot."
  [^File path]
  (.mkdirs (.getParentFile path))
  (.createNewFile path))

(defn- snapshot-path
  ^File [^File workflow-dir]
  (io/file workflow-dir (layout/snapshot-filename)))

(defn- snapshot-tmp-path
  ^File [^File workflow-dir]
  (io/file workflow-dir (layout/snapshot-tmp-filename)))

(defn- marker-path
  ^File [^File workflow-dir]
  (io/file workflow-dir (layout/archived-marker-filename)))

;------------------------------------------------------------------------------ Layer 1 — manifest-bound transitions over Layer 0

(defn- stage-archiving!
  "Step 3–4: update the manifest to `archive_status = :archiving`,
   merge any snapshot watermark, and fsync. The manifest writer
   itself is already atomic + fsynced, so the file-level guarantee
   comes for free; we only need to fsync the parent dir afterwards
   to commit the rename of the manifest tempfile."
  [^File live-dir {:keys [snapshot-watermark]}]
  (let [current (manifest/load-manifest live-dir)]
    (anomaly/let-ok [transitioned (manifest/transition-archive current :archiving)
                     staged-manifest (cond-> transitioned
                                       snapshot-watermark
                                       (assoc :snapshot_watermark snapshot-watermark))
                     _saved (manifest/save-manifest! live-dir staged-manifest)]
      (fsync-dir! live-dir)
      staged-manifest)))

(defn- complete-snapshot-rename!
  "Step 5: rename `snapshot.transit.json.tmp` → `snapshot.transit.json`
   when the tmp exists. Sub-3c writes the tmp; sub-3b tolerates its
   absence (the manifest's `snapshot_status` stays `:none`)."
  [^File workflow-dir]
  (let [tmp   (snapshot-tmp-path workflow-dir)
        final (snapshot-path workflow-dir)]
    (when (.exists tmp)
      (atomic-rename! tmp final))))

(defn- mark-archive-complete!
  "Step 7–8: stamp `archived_at` and `archive_status = :archived` on the
   manifest FIRST, then touch the archived marker, then fsync the archive
   directory plus its parent so both writes hit disk.

   Order matters for crash safety: writing the manifest before the marker
   ensures `scan-incomplete-archives` can detect a crash between the two
   steps (manifest `:archived` but marker absent) and trigger recovery.
   Writing the marker first would produce a directory that appears complete
   to the scanner even though the manifest still reads `:archiving`."
  [^File archived-dir]
  (let [m (manifest/load-manifest archived-dir)]
    (anomaly/let-ok [transitioned (manifest/transition-archive m :archived)
                     completed (assoc transitioned
                                      :archived_at (str (java.time.Instant/now)))
                     _saved (manifest/save-manifest! archived-dir completed)]
      (touch-marker! (marker-path archived-dir))
      (fsync-dir! archived-dir)
      (fsync-dir! (.getParentFile archived-dir))
      completed)))

;------------------------------------------------------------------------------ Layer 2 — workflow-bound orchestration over Layer 1

(defn archive-workflow!
  "Archive a workflow's directory from `live/{wid}/` to
   `archived/{wid}/`. Returns the final `archived/{wid}/` `File`.

   The full 8-step sequence:
     1. (caller wrote snapshot.transit.json.tmp earlier if applicable)
     2. fsync the snapshot tmp.
     3. Update manifest archive_status=:archiving + watermark.
     4. fsync manifest + parent dir (done inside `save-manifest!` +
        `stage-archiving!`).
     5. mv snapshot tmp → final.
     6. mv live/{wid} → archived/{wid}.
     7. Touch archived.marker and stamp `archived_at`.
     8. fsync archived/{wid} and its parent.

   Options:
     :snapshot-watermark   {:workflow_sequence_number long
                            :last_event_id (16-hex or nil)}
                          Merged into the manifest before archiving so
                          the consumer (BD-2c) sees the watermark on
                          read. Optional — sub-3c will pass it once
                          checkpoint synthesis is wired.
     :base-dir            Override base events dir (tests).

   Returns an anomaly on expected manifest state-machine/validation
   failures; throws on filesystem rename failures. Idempotent against
   partial state via `recover-incomplete-archive!` — running
   archive-workflow! on a directory whose manifest is already
   `:archiving` resumes from step 5 rather than re-staging from step 3."
  [workflow-id & [{:keys [base-dir] :as opts}]]
  (let [base       (or base-dir (sinks/default-events-dir))
        live-dir   (sinks/live-workflow-dir base workflow-id)
        archived   (sinks/archived-workflow-dir base workflow-id)
        current    (manifest/load-manifest live-dir)]
    (when-not current
      (throw (ex-info "Cannot archive workflow: no manifest at live dir"
                      {:reason :missing-manifest
                       :workflow-id workflow-id
                       :live-dir (.getAbsolutePath live-dir)})))
    ;; Steps 1–2: caller (BD-2c) is expected to have written the tmp.
    ;; We fsync it here regardless so the rename in step 5 has durable
    ;; contents to point at.
    (fsync-file! (snapshot-tmp-path live-dir))
    ;; Steps 3–4: stage the manifest as :archiving. Idempotent — if
    ;; we're recovering, the manifest is already :archiving and the
    ;; state machine rolls back to :live first (legal edge) before
    ;; re-transitioning.
    (anomaly/let-ok [_staged (when (= :live (:archive_status current))
                               (stage-archiving! live-dir opts))]
      ;; Step 5: rename the snapshot tmp into place.
      (complete-snapshot-rename! live-dir)
      ;; Step 6: atomic dir rename.
      (.mkdirs (.getParentFile archived))
      (atomic-rename! live-dir archived)
      ;; Steps 7–8: marker + archived_at + fsync.
      (anomaly/let-ok [_completed (mark-archive-complete! archived)]
        archived))))

(defn recover-incomplete-archive!
  "Resume a half-finished archive at `live-dir` (manifest's
   `archive_status` is `:archiving`) or `archived-dir` (missing
   `archived.marker`). Idempotent — completes whatever steps are
   outstanding and is a no-op when no recovery is needed.

   Recovery rules (see RFC §BD-2b atomic archive):
     - live/{wid} with `archive_status=:archiving` → finish steps 5–8.
     - archived/{wid} with no marker → finish steps 7–8.
     - Anything else: no-op."
  [base-dir workflow-id]
  (let [live-dir (sinks/live-workflow-dir base-dir workflow-id)
        archived (sinks/archived-workflow-dir base-dir workflow-id)
        in-live  (when (.exists live-dir) (manifest/load-manifest live-dir))
        in-archd (when (.exists archived) (manifest/load-manifest archived))]
    (cond
      ;; Live dir still exists with archive_status=:archiving — pick
      ;; up at step 5.
      (and in-live (= :archiving (:archive_status in-live)))
      (do (complete-snapshot-rename! live-dir)
          (.mkdirs (.getParentFile archived))
          (atomic-rename! live-dir archived)
          (anomaly/let-ok [_completed (mark-archive-complete! archived)]
            archived))
      ;; Live dir missing but archived dir lacks the marker — pick
      ;; up at step 7.
      (and in-archd (not (.exists (marker-path archived))))
      (mark-archive-complete! archived)
      ;; Nothing to do.
      :else nil)))

;------------------------------------------------------------------------------ Layer 3 — fleet-level scan + recover over Layer 2

(defn scan-incomplete-archives
  "List workflow-ids whose archive is half-completed under
   `base-dir`. Result is a vector of `{:workflow-id ... :state ...}`
   where `:state` is one of `:archiving-in-live` (step 5 onward
   pending) or `:archived-no-marker` (step 7 pending). Useful for
   the cleanup pass (sub-3c) and for startup recovery."
  [base-dir]
  (let [live     (sinks/live-dir base-dir)
        archived (sinks/archived-dir base-dir)]
    (vec
      (concat
        (for [^File d (when (.isDirectory live) (.listFiles live))
              :when (.isDirectory d)
              :let [m (manifest/load-manifest d)]
              :when (and m (= :archiving (:archive_status m)))]
          {:workflow-id (.getName d) :state :archiving-in-live})
        (for [^File d (when (.isDirectory archived) (.listFiles archived))
              :when (.isDirectory d)
              :when (not (.exists (marker-path d)))]
          {:workflow-id (.getName d) :state :archived-no-marker})))))

(defn recover-all-incomplete!
  "Walk `base-dir` and finish every half-completed archive found by
   `scan-incomplete-archives`. Returns a vector of recovered
   workflow-ids, or the first anomaly returned by recovery. Intended
   for boot-time recovery — production callers invoke this once before
   resuming normal operation."
  [base-dir]
  (let [result (reduce (fn [recovered {:keys [workflow-id]}]
                         (let [next-result (recover-incomplete-archive!
                                             base-dir workflow-id)]
                           (if (anomaly/anomaly? next-result)
                             (reduced next-result)
                             (conj recovered workflow-id))))
                       []
                       (scan-incomplete-archives base-dir))]
    (if (anomaly/anomaly? result)
      result
      (vec result))))
