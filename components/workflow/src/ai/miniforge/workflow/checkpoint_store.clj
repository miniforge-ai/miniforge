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
   [ai.miniforge.workflow.boundary.checkpoint-directory :as directory]
   [ai.miniforge.workflow.boundary.checkpoint-file :as checkpoint-file]
   [ai.miniforge.workflow.boundary.checkpoint-history :as history]
   [ai.miniforge.workflow.checkpoint-store-generation :as generation]
   [ai.miniforge.workflow.checkpoint-store-lock :as checkpoint-lock]
   [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
   [ai.miniforge.workflow.checkpoint-store-records :as checkpoint-records]
   [ai.miniforge.workflow.checkpoint-store-spec :as checkpoint-spec]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.schemas :as schemas]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

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

(defn- ^{:stratum 0} write-failure
  "The anomaly `persist-execution-state!` returns when a write fails: a
   full disk, a checkpoint root the process cannot write, an unreadable
   manifest. It names the run, the phase being checkpointed and the
   root, and keeps the exception's class and message (an I/O exception's
   message is usually the path), so the runner can say which write
   failed and why."
  [checkpoint-root workflow-run-id phase-name ex]
  (anomaly/exception-anomaly :unavailable
                             (messages/t :checkpoint/write-failed
                                         {:run-id workflow-run-id
                                          :error (or (ex-message ex) (.getName (class ex)))})
                             {:checkpoint/root checkpoint-root
                              :workflow/run-id workflow-run-id
                              :phase/name phase-name}
                             ex))

(defn- ^{:stratum 0} invalid-manifest
  "The anomaly `persist-execution-state!` returns, before writing
   anything, when the manifest already on disk parses but merges into a
   manifest that fails the checkpoint schema. Saving over it would
   persist metadata the loader rejects."
  [checkpoint-root workflow-run-id phase-name manifest-path validation]
  (anomaly/anomaly :invalid-input
                   (messages/t :checkpoint/write-failed
                               {:run-id workflow-run-id
                                :error (messages/t :checkpoint/manifest-invalid
                                                   {:path manifest-path})})
                   {:checkpoint/root checkpoint-root
                    :checkpoint/path manifest-path
                    :workflow/run-id workflow-run-id
                    :phase/name phase-name
                    :checkpoint/errors (get-in validation [:anomaly/data :errors])}))

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

(defn- ^{:stratum 0} pin-failure [workflow-run-id data e]
  (anomaly/exception-anomaly :unavailable
                             (messages/t :checkpoint/pin-failed
                                         {:run-id workflow-run-id
                                          :error (or (ex-message e) (.getName (class e)))})
                             data
                             e))

(defn- ^{:stratum 0} write-edn-atomically!
  [target-path data]
  (checkpoint-file/write! target-path data))

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

(defn- ^{:stratum 1} append-gate-history!
  "Append the phase's gate decision to the run's gate history when the
   phase result carries a decision envelope. Best-effort: a failed
   append logs nothing and changes nothing — the checkpoint write is the
   authority, this is the audit trail. `run-dir` is the run's resolved
   checkpoint directory."
  [run-dir phase-name phase-result ctx]
  (when-let [envelope (:phase/decision-envelope phase-result)]
    ;; Plain try, class-only catch at an absolute boundary (std 211 ex. a):
    ;; the audit append must never fail the checkpoint write.
    (try
      (let [entry {:phase phase-name
                   :at (str (java.time.Instant/now))
                   :decision (:envelope/decision envelope)
                   :redirect-count (get ctx :execution/redirect-count 0)
                   ;; The phase result's own keys — one canonical name per
                   ;; datum, so a history consumer reads what a checkpoint
                   ;; consumer reads.
                   :phase/gate-errors (:phase/gate-errors phase-result)
                   :phase/gate-failures (:phase/gate-failures phase-result)}]
        ;; Same instant normalization the checkpoint records get: gate
        ;; errors carry java.time.Instant values (policy-pack violations'
        ;; :timestamp), which pr-str would emit as #object[...] and make
        ;; the history unreadable.
        (history/append! (fs/path run-dir gate-history-filename)
                         (str (pr-str (coerce/stringify-instants entry)) "\n")))
      (catch Exception _ nil))))

(defn- ^{:stratum 1} write-pin-marker!
  "Write run `workflow-run-id`'s pin marker into its existing run
   directory `run-dir`, never creating it. The caller holds the run's
   lock. Returns the marker path, or an anomaly: `:not-found` when the run
   has no manifest, `:unavailable` when the marker cannot be written."
  [run-dir workflow-run-id]
  (let [path (str (fs/path run-dir checkpoint-paths/pin-marker-filename))
        data {:workflow/run-id workflow-run-id :checkpoint/path path}]
    (if-not (fs/exists? (fs/path run-dir checkpoint-paths/manifest-filename))
      (anomaly/anomaly :not-found
                       (messages/t :checkpoint/pin-no-manifest {:run-id workflow-run-id})
                       data)
      (try+
        (checkpoint-file/write! path {:pinned-at (str (java.time.Instant/now))}
                                {:prepare-directory! directory/existing!
                                 :prefix checkpoint-paths/pin-marker-temp-prefix})
        (catch Exception e
          (pin-failure workflow-run-id data e))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} pin-checkpoint!
  "Pin a run's checkpoint: write its pin marker, which the checkpoint GC
   never collects past. The marker is its own file, so a checkpoint write
   rewriting the manifest at the same time cannot drop it, and it is
   written under the run's lock (`checkpoint-store-lock`), which the GC
   holds while it decides to delete the run: the pin lands before that
   decision or finds the run gone. Returns the marker path, or an anomaly:
   `:not-found` when the run has no checkpoint, `:unavailable` when the
   lock stays busy, the marker cannot be written or anything else fails;
   it does not throw."
  ([workflow-run-id]
   (pin-checkpoint! workflow-run-id {}))
  ([workflow-run-id opts]
   (try+
     (let [checkpoint-root (checkpoint-paths/resolve-checkpoint-root opts)]
       (checkpoint-lock/with-run-lock
        checkpoint-root workflow-run-id
        #(write-pin-marker! (checkpoint-paths/workflow-checkpoint-dir checkpoint-root workflow-run-id)
                            workflow-run-id)))
     ;; Root resolution or the lock itself failing: still an anomaly.
     (catch Exception e
       (pin-failure workflow-run-id {:workflow/run-id workflow-run-id} e)))))

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

(defn ^{:stratum 2} read-checkpoint-manifest
  "A run's checkpoint manifest on its own, whatever the state of its
   snapshot: the manifest, nil when there is none, or an anomaly naming
   the file when it does not read. Resume checks the spec against it even
   when the snapshot cannot be restored."
  ([workflow-run-id]
   (read-checkpoint-manifest workflow-run-id {}))
  ([workflow-run-id opts]
   (read-checkpoint-file
    (checkpoint-paths/manifest-path (checkpoint-paths/resolve-checkpoint-root opts)
                                    workflow-run-id))))

(defn ^{:stratum 2} record-spec-path!
  "Point a run's manifest at where its spec file now lives (the CLI moves
   a work spec to work/done or work/failed when the run ends), keeping the
   recorded hash. Returns the manifest path; nil when the run has no
   readable manifest; an anomaly when it cannot be written."
  ([workflow-run-id spec-path]
   (record-spec-path! workflow-run-id spec-path {}))
  ([workflow-run-id spec-path opts]
   (let [path (checkpoint-paths/manifest-path (checkpoint-paths/resolve-checkpoint-root opts)
                                              workflow-run-id)
         manifest (read-checkpoint-file path)]
     (when (and (map? manifest) (not (anomaly/anomaly? manifest)))
       (try+
         (write-edn-atomically! path (assoc manifest :workflow/spec-path (str spec-path)))
         (catch Exception e
           (write-failure (checkpoint-paths/resolve-checkpoint-root opts) workflow-run-id nil e)))))))

(defn ^{:stratum 2} persist-execution-state!
  "Persist a machine snapshot, manifest, and current phase checkpoint,
   and append the phase's gate decision (if any) to the run's
   append-only gate history. A resumed run also gets a checkpoint for
   each result it was started holding (`:resume-phase-results`) that has
   none under its id: a resume under a new run id (a retried attempt, a
   rewind) can then itself be resumed with them.

   One write is one generation (`checkpoint-store-generation`): the
   snapshot and phase checkpoints go into a new generation directory, each
   forced to disk, and the manifest naming them is published last. A
   crash before the manifest leaves the previous checkpoint whole. The
   write holds the run's lock (`checkpoint-store-lock`) throughout, so a
   pin, a GC or another write of the same run waits for it. Before it
   creates its generation, a write removes the generations the current
   manifest does not name (those it replaced, and any a failed write left).
   Every phase result the manifest names no readable file for is written
   too, so a phase whose write failed is not lost. A pending interrupt is
   held off for the write and restored after it.

   Returns the written paths (with `:checkpoint/phase` and
   `:checkpoint/phase-path` when a phase checkpoint was written), nil for
   a context with no run id, or — when a read or write fails or the lock
   stays busy — an `:unavailable` anomaly naming the run, phase and root;
   an existing manifest that would merge into an invalid one is an
   `:invalid-input` anomaly, with no checkpoint file written. A failed
   write is reported, not thrown: the caller decides whether the run goes
   on without a checkpoint. A context that fails the checkpoint schema
   still throws; that is a programmer error, not an I/O condition.

   The root and run directory are resolved once, before anything is read
   or written, and every read and write uses them: an alias retargeted
   during the write cannot split one checkpoint across directories. The
   result's `:checkpoint/root` is the resolved root, and the manifest
   records each file at the path it was published at."
  [ctx]
  (when-let [workflow-run-id (:execution/id ctx)]
    (let [requested-root (checkpoint-paths/resolve-checkpoint-root ctx)
          phase-name (checkpoint-records/active-or-last-phase ctx)
          phase-result (get-in ctx [:execution/phase-results phase-name])
          snapshot (checkpoint-records/build-machine-snapshot ctx)
          checkpoint-data {:checkpoint/root requested-root
                           :manifest (checkpoint-records/build-manifest ctx requested-root)
                           :machine-snapshot snapshot
                           :phase-results (or (:execution/phase-results ctx) {})}
          _ (schemas/validate-checkpoint-data! checkpoint-data)
          ;; Interruptible channel I/O and lock waits would fail on it.
          interrupted? (Thread/interrupted)]
      ;; Every read and write below is I/O: a failure becomes the returned
      ;; anomaly, never an exception that would end the run. The existing
      ;; manifest is read here, inside the guard, for the same reason, and
      ;; the manifest actually written (merged with it) is validated
      ;; before anything is written.
      (try+
        (let [checkpoint-root (str (directory/prepare! requested-root))
              written
              (checkpoint-lock/with-run-lock
               checkpoint-root workflow-run-id
               (fn []
                 (let [run-dir (directory/prepare! (checkpoint-paths/workflow-checkpoint-dir
                                                    checkpoint-root workflow-run-id))
                       manifest-path (str (fs/path run-dir checkpoint-paths/manifest-filename))
                       existing-manifest (read-edn-file manifest-path)
                       existing-paths (or (:workflow/phase-checkpoints existing-manifest) {})
                       merged-manifest (merge (checkpoint-records/build-manifest ctx checkpoint-root
                                                                                 existing-manifest)
                                              (checkpoint-spec/spec-fields ctx existing-manifest))
                       validation (schemas/validate-checkpoint-data
                                   (assoc checkpoint-data
                                          :checkpoint/root checkpoint-root
                                          :manifest merged-manifest))]
                   (if (anomaly/anomaly? validation)
                     (invalid-manifest checkpoint-root workflow-run-id phase-name manifest-path validation)
                     (let [_ (try
                               ;; Best effort, class-only catch at an absolute
                               ;; boundary (std 211 ex. a): the next write retries.
                               (generation/prune! run-dir [existing-manifest])
                               (catch Exception _ nil))
                           generation (generation/create! run-dir)
                           to-write (cond-> (into {}
                                                  (remove (fn [[phase result]]
                                                            (or (nil? result)
                                                                (some-> (get existing-paths phase) fs/exists?))))
                                                  (merge (get-in ctx [:execution/opts :resume-phase-results])
                                                         (:execution/phase-results ctx)))
                                      (and phase-name phase-result) (assoc phase-name phase-result))
                           phase-paths (into {}
                                             (map (fn [[phase result]]
                                                    [phase (write-edn-atomically!
                                                            (generation/phase-path generation phase)
                                                            (checkpoint-records/build-phase-checkpoint ctx phase result))]))
                                             to-write)
                           phase-path (when (and phase-name phase-result) (get phase-paths phase-name))
                           _ (when phase-path
                               (append-gate-history! run-dir phase-name phase-result ctx))
                           snapshot-path (write-edn-atomically! (generation/snapshot-path generation) snapshot)
                           manifest (assoc merged-manifest
                                           :workflow/machine-snapshot-path snapshot-path
                                           :workflow/phase-checkpoints (merge existing-paths phase-paths))
                           manifest-path (write-edn-atomically! manifest-path manifest)
                           ;; A failed initial pin must remain a failed checkpoint.
                           pin (when (and (true? (get-in ctx [:execution/opts :checkpoint/pin?]))
                                          (not (fs/exists? (fs/path run-dir checkpoint-paths/pin-marker-filename))))
                                 (write-pin-marker! run-dir workflow-run-id))]
                       (if (anomaly/anomaly? pin)
                         pin
                         (cond-> {:checkpoint/root checkpoint-root
                                  :checkpoint/machine-snapshot-path snapshot-path
                                  :checkpoint/manifest-path manifest-path}
                           phase-path
                           (assoc :checkpoint/phase phase-name
                                  :checkpoint/phase-path phase-path))))))))]
          (if (anomaly/anomaly? written)
            (assoc-in written [:anomaly/data :phase/name] phase-name)
            written))
        (catch InterruptedException e
          (.interrupt (Thread/currentThread))
          (write-failure requested-root workflow-run-id phase-name e))
        (catch Exception e
          (write-failure requested-root workflow-run-id phase-name e))
        (finally
          (when interrupted? (.interrupt (Thread/currentThread))))))))
