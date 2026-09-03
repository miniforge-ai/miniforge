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
   [ai.miniforge.coerce.interface :as coerce]
   [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
   [ai.miniforge.workflow.checkpoint-store-records :as checkpoint-records]
   [ai.miniforge.workflow.schemas :as schemas]
   [babashka.fs :as fs]
   [clojure.edn :as edn]))

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

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} write-edn-atomically!
  [target-path data]
  (let [target (fs/file target-path)
        temp-path (str target-path temp-file-suffix)]
    (fs/create-dirs (fs/parent target))
    (spit temp-path (pr-str data))
    (when (fs/exists? target-path)
      (fs/delete target-path))
    (fs/move temp-path target-path)
    target-path))

(defn ^{:stratum 1} load-checkpoint-data
  "Load durable checkpoint data for a workflow run, if present."
  ([workflow-run-id]
   (load-checkpoint-data workflow-run-id {}))
  ([workflow-run-id opts]
   (try
     (let [checkpoint-root (checkpoint-paths/resolve-checkpoint-root opts)
           manifest-path (checkpoint-paths/manifest-path checkpoint-root workflow-run-id)
           manifest (read-edn-file manifest-path)
           snapshot-path (or (:workflow/machine-snapshot-path manifest)
                             (checkpoint-paths/machine-snapshot-path checkpoint-root
                                                                     workflow-run-id))
           machine-snapshot (read-edn-file snapshot-path)
           phase-paths (or (:workflow/phase-checkpoints manifest) {})
           phase-results (into {}
                               (keep (fn [[phase-id path]]
                                       (when-let [checkpoint (read-edn-file path)]
                                         [phase-id (:phase/result checkpoint)])))
                               phase-paths)]
       (when machine-snapshot
         {:checkpoint/root checkpoint-root
          :manifest manifest
          :machine-snapshot machine-snapshot
          :phase-results phase-results}))
     (catch Exception _
       nil))))

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
        (with-open [w (java.io.FileOutputStream. (fs/file dir gate-history-filename) true)]
          (.write w (.getBytes (str (pr-str entry) "\n") "UTF-8"))))
      (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} persist-execution-state!
  "Persist a machine snapshot, manifest, and current phase checkpoint,
   and append the phase's gate decision (if any) to the run's
   append-only gate history."
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
