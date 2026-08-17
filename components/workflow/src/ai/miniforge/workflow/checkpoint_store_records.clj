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
(ns ai.miniforge.workflow.checkpoint-store-records
  "What a checkpoint contains: the machine snapshot, the per-phase
   record, and the manifest that indexes them.

   Every builder here is a pure projection of an execution context —
   given the same context it returns the same map, and none of them
   touch the filesystem. `persisted-execution-keys`, the allowlist
   deciding what survives a checkpoint at all, lives here with
   `build-machine-snapshot`, the one function that applies it.

   Split out of `checkpoint-store`, which held path resolution, record
   building and persistence as one five-stratum chain in a single file
   (rule 210 caps a file at three). `checkpoint-store-paths` answers
   where a record goes; `checkpoint-store` writes it."
  (:require
   [ai.miniforge.coerce.interface :as coerce]
   [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} current-checkpoint-timestamp
  []
  (str (java.time.Instant/now)))

(def ^{:stratum 0} persisted-execution-keys
  "Serializable execution fields kept in the durable machine snapshot."
  [:execution/id
   :execution/workflow-id
   :execution/workflow-version
   :execution/input
   :execution/status
   :execution/current-phase
   :execution/phase-index
   :execution/redirect-count
   :execution/fsm-state
   :execution/response-chain
   :execution/errors
   :execution/phase-handoffs
   :execution/artifacts
   :execution/dag-result
   :execution/dag-pr-infos
   :execution/metrics
   :execution/output
   :execution/started-at
   :execution/ended-at
   :execution/environment-id
   :execution/environment-metadata
   :execution/worktree-path
   :execution/mode
   :execution/completed-with-warnings?])

(defn ^{:stratum 0} ordered-phase-ids
  "Phase ids in workflow pipeline order, filtered to checkpointed phases."
  [ctx]
  (let [phase-results (:execution/phase-results ctx)
        pipeline-phase-ids (map :phase (get-in ctx [:execution/workflow :workflow/pipeline]))
        ordered-phase-ids (filter #(contains? phase-results %) pipeline-phase-ids)
        remaining-phase-ids (remove (set ordered-phase-ids) (keys phase-results))]
    (vec (concat ordered-phase-ids remaining-phase-ids))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} active-or-last-phase
  "Current phase when present, otherwise the last checkpointed phase."
  [ctx]
  (or (:execution/current-phase ctx)
      (last (ordered-phase-ids ctx))))

(defn ^{:stratum 1} build-machine-snapshot
  "Build the durable machine snapshot for an execution context.

   Timestamps are normalized by type on the way out. Writers of
   :execution/started-at / :execution/ended-at disagree today
   (context.clj writes epoch millis, runner.clj an Instant), and
   `inst?` admits a Date as readily as an Instant — so without
   normalization a restore hands its reader an Instant-derived string
   from one writer and a live Date from another for the same key."
  [ctx]
  (-> (select-keys ctx persisted-execution-keys)
      coerce/stringify-instants))

(defn ^{:stratum 1} build-phase-checkpoint
  "Build a durable per-phase checkpoint record."
  [ctx phase-name phase-result]
  (let [checkpointed-at (current-checkpoint-timestamp)]
    (coerce/stringify-instants
     {:workflow/id (:execution/id ctx)
      :workflow/workflow-id (:execution/workflow-id ctx)
      :workflow/workflow-version (:execution/workflow-version ctx)
      :workflow/phase phase-name
      :workflow/checkpointed-at checkpointed-at
      :phase/result phase-result})))

(defn ^{:stratum 1} build-manifest
  "Build the durable checkpoint manifest for an execution context."
  ([ctx checkpoint-root]
   (build-manifest ctx checkpoint-root nil))
  ([ctx checkpoint-root existing-manifest]
   (let [workflow-run-id (:execution/id ctx)
         current-phase-ids (ordered-phase-ids ctx)
         existing-phase-paths (or (:workflow/phase-checkpoints existing-manifest) {})
         existing-phase-ids (or (not-empty (:workflow/phases-completed existing-manifest))
                                (sort-by name (keys existing-phase-paths)))
         phase-ids (vec (concat existing-phase-ids
                                (remove (set existing-phase-ids)
                                        current-phase-ids)))
         current-phase-paths (into {}
                                   (map (fn [phase-id]
                                          [phase-id
                                           (checkpoint-paths/phase-checkpoint-path
                                            checkpoint-root
                                            workflow-run-id
                                            phase-id)]))
                                   current-phase-ids)
         phase-paths (merge existing-phase-paths current-phase-paths)]
     (coerce/stringify-instants
      {:workflow/id workflow-run-id
       :workflow/workflow-id (:execution/workflow-id ctx)
       :workflow/workflow-version (:execution/workflow-version ctx)
       :workflow/phases-completed phase-ids
       :workflow/machine-snapshot-path
       (checkpoint-paths/machine-snapshot-path checkpoint-root workflow-run-id)
       :workflow/phase-checkpoints phase-paths
       :workflow/last-checkpoint-at (current-checkpoint-timestamp)}))))
