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
(ns ai.miniforge.workflow.checkpoint-store
  "Durable execution-machine snapshots and phase checkpoints: the two
   operations that move a checkpoint between memory and disk.

   `persist-execution-state!` writes a run's snapshot, manifest and
   current phase checkpoint; `load-checkpoint-data` reads them back.
   Both go through the same EDN read/write pair below, so the instant
   normalization a restore depends on is a property of the store rather
   than of whichever version wrote the file.

   Two siblings carry what used to be the lower half of this chain,
   which stood at five strata against rule 210's cap of three:
   `checkpoint-store-paths` resolves the checkpoint root and every
   per-run path; `checkpoint-store-records` builds the snapshot,
   per-phase and manifest records — including
   `persisted-execution-keys`, the allowlist deciding what survives a
   checkpoint."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.coerce.interface :as coerce]
   [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
   [ai.miniforge.workflow.checkpoint-store-records :as checkpoint-records]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.schemas :as schemas]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [slingshot.slingshot :refer [try+]])
  (:import [java.nio.file Files StandardCopyOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} temp-file-suffix
  "Temporary suffix used for atomic checkpoint writes."
  ".tmp")

(defn- ^{:stratum 0} read-edn-file
  "Read a checkpoint EDN file, normalizing instants the same way the
   write side does.

   Checkpoints already on disk were written before this normalization
   existed, so a `java.util.Date` in one of them was persisted as
   `#inst` — and EDN's reader hands that back as a Date, which is
   exactly the mixed-type restore this file is trying to stop. Reading
   through the same normalizer makes the guarantee a property of the
   store rather than of whichever version wrote the file."
  [path]
  (when (fs/exists? path)
    (coerce/stringify-instants (edn/read-string (slurp path)))))

(def ^{:stratum 0} gate-history-filename
  "Append-only per-run log of every gate decision, one pr-str'd entry per
   line. The per-phase checkpoint is OVERWRITTEN on phase re-entry (a
   redirect loop keeps only its last iteration), which is exactly the
   record a gate-denied-then-retried phase needs and lost — the
   trap-bench repair demonstrations could not attribute their retries.
   Same append discipline as the codex-gap ledger: one O_APPEND write, a
   failed append cannot damage prior entries."
  "gate-history.edn")

;; Existence
(defn ^{:stratum 0} checkpoint-present?
  "True when anything is stored under `workflow-run-id`'s checkpoint
   directory. `load-checkpoint-data` answers nil for a run with none, but
   also for phase checkpoints written before the machine snapshot (a run
   that died between the two), and an anomaly when its snapshot or
   manifest cannot be read or parsed. An id is free only when this is
   false."
  ([workflow-run-id]
   (checkpoint-present? workflow-run-id {}))
  ([workflow-run-id opts]
   (let [dir (checkpoint-paths/workflow-checkpoint-dir
              (checkpoint-paths/resolve-checkpoint-root opts)
              workflow-run-id)]
     (boolean (and (fs/directory? dir) (seq (fs/list-dir dir)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-checkpoint-file
  "Read one checkpoint file: its content, nil when it does not exist, or
   — when it exists but cannot be read or parsed (truncated, unreadable,
   not EDN), holds no record (empty, whitespace, a bare value), or was
   written in a checkpoint format newer than this build reads — an
   anomaly naming the file. One bad file then costs the caller that file
   and nothing else."
  [path]
  (try+
    (let [content (read-edn-file path)]
      (cond
        (not (fs/exists? path)) content
        (not (map? content))
        (anomaly/anomaly :unavailable
                         (messages/t :checkpoint/read-failed
                                     {:path (str path)
                                      :error (messages/t :checkpoint/no-record)})
                         {:checkpoint/path (str path)})
        (checkpoint-records/readable-format? content) content
        :else
        (anomaly/anomaly :unsupported
                         (messages/t :checkpoint/newer-format
                                     {:path (str path)
                                      :version (pr-str (:checkpoint/format-version content))
                                      :supported checkpoint-records/checkpoint-format-version})
                         {:checkpoint/path (str path)
                          :checkpoint/format-version (:checkpoint/format-version content)
                          :checkpoint/supported-format-version
                          checkpoint-records/checkpoint-format-version})))
    (catch Exception e
      (anomaly/exception-anomaly :unavailable
                                 (messages/t :checkpoint/read-failed
                                             {:path (str path)
                                              :error (or (ex-message e) (.getName (class e)))})
                                 {:checkpoint/path (str path)}
                                 e))))

(defn- ^{:stratum 1} write-edn-atomically!
  [target-path data]
  (let [target (fs/file target-path)
        temp-path (str target-path temp-file-suffix)]
    (fs/create-dirs (fs/parent target))
    (spit temp-path (pr-str data))
    (Files/move (fs/path temp-path)
               (fs/path target-path)
               (into-array java.nio.file.CopyOption
                           [StandardCopyOption/ATOMIC_MOVE
                            StandardCopyOption/REPLACE_EXISTING]))
    target-path))

(defn- ^{:stratum 1} append-gate-history!
  "Append the phase's gate decision to the run's gate history when the
   phase result carries a decision envelope. Best-effort: a failed
   append logs nothing and changes nothing — the checkpoint write is the
   authority, this is the audit trail."
  [checkpoint-root workflow-run-id phase-name phase-result ctx]
  (when-let [envelope (:phase/decision-envelope phase-result)]
    ;; Plain try, class-only catch at an absolute boundary (std 211 ex. a):
    ;; the audit append must never fail the checkpoint write.
    (try
      (let [dir (checkpoint-paths/workflow-checkpoint-dir checkpoint-root workflow-run-id)
            entry {:phase phase-name
                   :at (str (java.time.Instant/now))
                   :decision (:envelope/decision envelope)
                   :redirect-count (get ctx :execution/redirect-count 0)
                   ;; The phase result's own keys — one canonical name per
                   ;; datum, so a history consumer reads what a checkpoint
                   ;; consumer reads.
                   :phase/gate-errors (:phase/gate-errors phase-result)
                   :phase/gate-failures (:phase/gate-failures phase-result)}]
        (fs/create-dirs dir)
        ;; Same instant normalization the checkpoint records get: gate
        ;; errors carry java.time.Instant values (policy-pack violations'
        ;; :timestamp), which pr-str would emit as #object[...] and make
        ;; the history unreadable.
        (with-open [w (java.io.FileOutputStream. (fs/file dir gate-history-filename) true)]
          (.write w (.getBytes (str (pr-str (coerce/stringify-instants entry)) "\n") "UTF-8"))))
      (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-checkpoint-data
  "Load durable checkpoint data for a workflow run, one file at a time.

   Returns nil when the run has no machine snapshot. Returns the loaded
   data when the snapshot and manifest read; a phase checkpoint that
   does not read is left out of `:phase-results` and listed, as an
   anomaly naming the file, under `:checkpoint/read-failures` — the
   snapshot and every other phase still load; the anomaly carries the
   phase's `:phase/name`, so a resume can rewind to it. When the snapshot or the
   manifest exists but does not read, nothing can be restored: the
   result is an `:unavailable` anomaly whose
   `:checkpoint/read-failures` names every file that failed."
  ([workflow-run-id]
   (load-checkpoint-data workflow-run-id {}))
  ([workflow-run-id opts]
   (let [checkpoint-root (checkpoint-paths/resolve-checkpoint-root opts)
         manifest (read-checkpoint-file
                   (checkpoint-paths/manifest-path checkpoint-root workflow-run-id))
         readable-manifest (when-not (anomaly/anomaly? manifest) manifest)
         machine-snapshot (read-checkpoint-file
                           (or (:workflow/machine-snapshot-path readable-manifest)
                               (checkpoint-paths/machine-snapshot-path checkpoint-root
                                                                       workflow-run-id)))
         phase-checkpoints (into []
                                 (map (fn [[phase-id path]]
                                        (let [checkpoint (read-checkpoint-file path)]
                                          [phase-id (cond-> checkpoint
                                                      (anomaly/anomaly? checkpoint)
                                                      (assoc-in [:anomaly/data :phase/name] phase-id))])))
                                 (:workflow/phase-checkpoints readable-manifest))
         read-failures (filterv anomaly/anomaly?
                                (into [manifest machine-snapshot] (map second) phase-checkpoints))
         phase-results (into {}
                             (keep (fn [[phase-id checkpoint]]
                                     (when (and checkpoint (not (anomaly/anomaly? checkpoint)))
                                       [phase-id (:phase/result checkpoint)])))
                             phase-checkpoints)]
     (cond
       (or (anomaly/anomaly? manifest) (anomaly/anomaly? machine-snapshot))
       (anomaly/anomaly :unavailable
                        (messages/t :checkpoint/unreadable
                                    {:run-id workflow-run-id
                                     :files (->> read-failures
                                                 (map :anomaly/message)
                                                 (interpose "; ")
                                                 (apply str))})
                        {:workflow/run-id workflow-run-id
                         :checkpoint/root checkpoint-root
                         :checkpoint/read-failures read-failures})

       machine-snapshot
       (cond-> {:checkpoint/root checkpoint-root
                :manifest manifest
                :machine-snapshot machine-snapshot
                :phase-results phase-results}
         (seq read-failures) (assoc :checkpoint/read-failures read-failures))

       :else nil))))

(defn ^{:stratum 2} persist-execution-state!
  "Persist a machine snapshot, manifest, and current phase checkpoint,
   and append the phase's gate decision (if any) to the run's
   append-only gate history. A resumed run also gets a checkpoint for
   each result it was started holding (`:resume-phase-results`) that has
   none under its id: a resume under a new run id (a retried attempt, a
   rewind) can then itself be resumed with them."
  [ctx]
  (when-let [workflow-run-id (:execution/id ctx)]
    (let [checkpoint-root (checkpoint-paths/resolve-checkpoint-root ctx)
          phase-name (checkpoint-records/active-or-last-phase ctx)
          phase-result (get-in ctx [:execution/phase-results phase-name])
          snapshot-path (checkpoint-paths/machine-snapshot-path checkpoint-root
                                                                workflow-run-id)
          manifest-path (checkpoint-paths/manifest-path checkpoint-root workflow-run-id)
          existing-manifest (read-edn-file manifest-path)
          snapshot (checkpoint-records/build-machine-snapshot ctx)
          manifest (checkpoint-records/build-manifest ctx checkpoint-root existing-manifest)
          checkpoint-data {:checkpoint/root checkpoint-root
                           :manifest manifest
                           :machine-snapshot snapshot
                           :phase-results (or (:execution/phase-results ctx) {})}]
      (schemas/validate-checkpoint-data! checkpoint-data)
      (try
        (doseq [[resumed-phase resumed-result] (get-in ctx [:execution/opts :resume-phase-results])
                :let [path (checkpoint-paths/phase-checkpoint-path checkpoint-root workflow-run-id resumed-phase)]
                :when (and resumed-result (not (fs/exists? path)))]
          (write-edn-atomically! path (checkpoint-records/build-phase-checkpoint ctx resumed-phase resumed-result)))
        (when (and phase-name phase-result)
          (write-edn-atomically!
           (checkpoint-paths/phase-checkpoint-path checkpoint-root
                                                   workflow-run-id
                                                   phase-name)
           (checkpoint-records/build-phase-checkpoint ctx phase-name phase-result))
          (append-gate-history! checkpoint-root workflow-run-id phase-name phase-result ctx))
        (write-edn-atomically! snapshot-path snapshot)
        (write-edn-atomically! manifest-path manifest)
        {:checkpoint/root checkpoint-root
         :checkpoint/machine-snapshot-path snapshot-path
         :checkpoint/manifest-path manifest-path}
        (catch Exception _
          nil)))))
