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
  "Durable execution-machine snapshots and phase checkpoints."
  (:require
   [ai.miniforge.config.interface :as config]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.walk :as walk]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and path resolution

(def checkpoint-root-option-key
  "Execution option key for overriding the checkpoint root."
  :checkpoint/root)

(def machine-snapshot-filename
  "Filename for the authoritative execution-machine snapshot."
  "machine-snapshot.edn")

(def manifest-filename
  "Filename for the durable workflow checkpoint manifest."
  "manifest.edn")

(def phase-checkpoints-directory-name
  "Directory name for per-phase checkpoint files."
  "phases")

(def temp-file-suffix
  "Temporary suffix used for atomic checkpoint writes."
  ".tmp")

(defn- current-checkpoint-timestamp
  []
  (str (java.time.Instant/now)))

(defn- serialize-checkpoint-value
  [value]
  (walk/postwalk
   (fn [node]
     (if (instance? java.time.Instant node)
       (str node)
       node))
   value))

(def persisted-execution-keys
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
   :execution/artifacts
   :execution/metrics
   :execution/output
   :execution/started-at
   :execution/ended-at
   :execution/environment-id
   :execution/worktree-path
   :execution/mode
   :execution/completed-with-warnings?])

(defn- normalize-checkpoint-root
  [checkpoint-root]
  (some-> checkpoint-root fs/expand-home str))

(defn default-checkpoint-root
  "Default durable checkpoint root from merged config."
  []
  (normalize-checkpoint-root
   (or (get-in (config/load-merged-config) [:workflow :checkpoint-root])
       (str (config/miniforge-home) "/checkpoints"))))

(defn resolve-checkpoint-root
  "Resolve checkpoint root from context/opts/config."
  ([]
   (default-checkpoint-root))
  ([m]
   (normalize-checkpoint-root
    (or (:execution/checkpoint-root m)
        (get m checkpoint-root-option-key)
        (get-in m [:execution/opts checkpoint-root-option-key])
        (default-checkpoint-root)))))

(defn workflow-checkpoint-dir
  "Directory for one workflow run's durable checkpoint state."
  [checkpoint-root workflow-run-id]
  (str (fs/path checkpoint-root (str workflow-run-id))))

(defn machine-snapshot-path
  "Path to the authoritative machine snapshot for a workflow run."
  [checkpoint-root workflow-run-id]
  (str (fs/path (workflow-checkpoint-dir checkpoint-root workflow-run-id)
                machine-snapshot-filename)))

(defn manifest-path
  "Path to the manifest for a workflow run."
  [checkpoint-root workflow-run-id]
  (str (fs/path (workflow-checkpoint-dir checkpoint-root workflow-run-id)
                manifest-filename)))

(defn phase-checkpoints-dir
  "Directory that stores per-phase checkpoint files."
  [checkpoint-root workflow-run-id]
  (str (fs/path (workflow-checkpoint-dir checkpoint-root workflow-run-id)
                phase-checkpoints-directory-name)))

(defn phase-checkpoint-path
  "Path to a persisted phase checkpoint."
  [checkpoint-root workflow-run-id phase-name]
  (str (fs/path (phase-checkpoints-dir checkpoint-root workflow-run-id)
                (str (name phase-name) ".edn"))))

;------------------------------------------------------------------------------ Layer 0.5
;; Serialization helpers

(defn- write-edn-atomically!
  [target-path data]
  (let [target (fs/file target-path)
        temp-path (str target-path temp-file-suffix)]
    (fs/create-dirs (fs/parent target))
    (spit temp-path (pr-str data))
    (when (fs/exists? target-path)
      (fs/delete target-path))
    (fs/move temp-path target-path)
    target-path))

(defn- read-edn-file
  [path]
  (when (fs/exists? path)
    (edn/read-string (slurp path))))

(defn ordered-phase-ids
  "Phase ids in workflow pipeline order, filtered to checkpointed phases."
  [ctx]
  (let [phase-results (:execution/phase-results ctx)
        pipeline-phase-ids (map :phase (get-in ctx [:execution/workflow :workflow/pipeline]))
        ordered-phase-ids (filter #(contains? phase-results %) pipeline-phase-ids)
        remaining-phase-ids (remove (set ordered-phase-ids) (keys phase-results))]
    (vec (concat ordered-phase-ids remaining-phase-ids))))

(defn active-or-last-phase
  "Current phase when present, otherwise the last checkpointed phase."
  [ctx]
  (or (:execution/current-phase ctx)
      (last (ordered-phase-ids ctx))))

(defn build-machine-snapshot
  "Build the durable machine snapshot for an execution context."
  [ctx]
  (-> (select-keys ctx persisted-execution-keys)
      serialize-checkpoint-value))

(defn build-phase-checkpoint
  "Build a durable per-phase checkpoint record."
  [ctx phase-name phase-result]
  (let [checkpointed-at (current-checkpoint-timestamp)]
    (serialize-checkpoint-value
     {:workflow/id (:execution/id ctx)
      :workflow/workflow-id (:execution/workflow-id ctx)
      :workflow/workflow-version (:execution/workflow-version ctx)
      :workflow/phase phase-name
      :workflow/checkpointed-at checkpointed-at
      :phase/result phase-result})))

(defn build-manifest
  "Build the durable checkpoint manifest for an execution context."
  [ctx checkpoint-root]
  (let [workflow-run-id (:execution/id ctx)
        phase-ids (ordered-phase-ids ctx)
        phase-paths (into {}
                          (map (fn [phase-id]
                                 [phase-id
                                  (phase-checkpoint-path checkpoint-root workflow-run-id phase-id)]))
                          phase-ids)]
    (serialize-checkpoint-value
     {:workflow/id workflow-run-id
      :workflow/workflow-id (:execution/workflow-id ctx)
      :workflow/workflow-version (:execution/workflow-version ctx)
      :workflow/phases-completed phase-ids
      :workflow/machine-snapshot-path
      (machine-snapshot-path checkpoint-root workflow-run-id)
      :workflow/phase-checkpoints phase-paths
      :workflow/last-checkpoint-at (current-checkpoint-timestamp)})))

;------------------------------------------------------------------------------ Layer 1
;; Persistence and restore inputs

(defn persist-execution-state!
  "Persist a machine snapshot, manifest, and current phase checkpoint."
  [ctx]
  (try
    (when-let [workflow-run-id (:execution/id ctx)]
      (let [checkpoint-root (resolve-checkpoint-root ctx)
            phase-name (active-or-last-phase ctx)
            phase-result (get-in ctx [:execution/phase-results phase-name])
            snapshot-path' (machine-snapshot-path checkpoint-root workflow-run-id)
            manifest-path' (manifest-path checkpoint-root workflow-run-id)
            snapshot (build-machine-snapshot ctx)
            manifest (build-manifest ctx checkpoint-root)]
        (when (and phase-name phase-result)
          (write-edn-atomically!
           (phase-checkpoint-path checkpoint-root workflow-run-id phase-name)
           (build-phase-checkpoint ctx phase-name phase-result)))
        (write-edn-atomically! snapshot-path' snapshot)
        (write-edn-atomically! manifest-path' manifest)
        {:checkpoint/root checkpoint-root
         :checkpoint/machine-snapshot-path snapshot-path'
         :checkpoint/manifest-path manifest-path'}))
    (catch Exception _
      nil)))

(defn load-checkpoint-data
  "Load durable checkpoint data for a workflow run, if present."
  ([workflow-run-id]
   (load-checkpoint-data workflow-run-id {}))
  ([workflow-run-id opts]
   (try
     (let [checkpoint-root (resolve-checkpoint-root opts)
           manifest-path' (manifest-path checkpoint-root workflow-run-id)
           manifest (read-edn-file manifest-path')
           snapshot-path' (or (:workflow/machine-snapshot-path manifest)
                              (machine-snapshot-path checkpoint-root workflow-run-id))
           machine-snapshot (read-edn-file snapshot-path')
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
