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

(ns ai.miniforge.workbench-orchestration-adapter.core
  "Pure assembly of a Miniforge orchestration workbench_snapshot/v1 wire map."
  (:require
   [ai.miniforge.workbench-orchestration-adapter.config :as config]
   [ai.miniforge.workbench-orchestration-adapter.evaluation :as evaluation]))

(def ^:private product "miniforge")
(def ^:private snapshot-schema-version "workbench_snapshot/v1")
(def ^:private signature-stub
  {:algorithm "sha256"
   :digest "0000000000000000000000000000000000000000000000000000000000000000"
   :canonicalization "json-c14n/1"})

(defn- iso-instant
  "Render an entity timestamp (java.util.Date or java.time.Instant) as an
   ISO-8601 string. Pure - never reads a clock."
  [timestamp]
  (cond
    (instance? java.util.Date timestamp) (str (.toInstant ^java.util.Date timestamp))
    (some? timestamp) (str timestamp)
    :else nil))

(defn- variant [run experiment-id label]
  {:experiment_id experiment-id
   :label label
   :workflow {:id (:workflow-run/workflow-key run)}
   :method "orchestration"
   :axes {:workflow_key (:workflow-run/workflow-key run)
          :terminal_status (name (:workflow-run/status run))}})

(defn- workflow-run-entity [run]
  (cond-> {:workflow/id (str (:workflow-run/id run))
           :workflow/status (name (:workflow-run/status run))
           :workflow/current-phase (name (:workflow-run/current-phase run))
           :workflow/workflow-key (:workflow-run/workflow-key run)
           :workflow/started-at (iso-instant (:workflow-run/started-at run))
           :workflow/updated-at (iso-instant (:workflow-run/updated-at run))
           :workflow/pr-count (count (:workflow-run/prs run))}
    (:workflow-run/evidence-bundle-id run)
    (assoc :workflow/evidence-bundle-id
           (str (:workflow-run/evidence-bundle-id run)))))

(defn snapshot
  "Project one resolved workflow run from `table` into a snapshot.

   Pure projection: `generated_at` is caller-supplied via `:generated-at`
   and defaults to the run's own `:workflow-run/updated-at` - no clock is
   read here (same purity discipline as the ETL adapter)."
  [table run {:keys [experiment-id label snapshot-id run-id generated-at
                     source-hashes transitions phase-history registry]}]
  (let [phase-history (vec phase-history)
        run-id        (str (or run-id (:workflow-run/id run)))
        snapshot-id   (str (or snapshot-id (str "wb-" run-id)))
        generated-at  (iso-instant (or generated-at
                                       (:workflow-run/updated-at run)))]
    (cond->
     {:schema_version snapshot-schema-version
      :snapshot_id snapshot-id
      :generated_at generated-at
      :product product
      :run_id run-id
      :variant (variant run experiment-id label)
      ;; Sourced from the loaded registry (validated by the caller), never
      ;; from constants — snapshots can't drift from the exported registry.
      :registry_ref {:registry_id (:registry_id registry)
                     :version (:version registry)}
      :evaluations (evaluation/evaluations {:run run
                                            :table table
                                            :transitions transitions
                                            :phase-history phase-history
                                            :evaluated-at generated-at})
      :entities {:miniforge.workflow_run (workflow-run-entity run)}
      :metadata
      {:provenance (evaluation/provenance)
       :resolved_run {:schema_version config/resolved-run-schema-version
                      :domain "orchestration"
                      :terminal_status (name (:workflow-run/status run))
                      :phase_history (mapv name phase-history)
                      :observed_transition_count (max 0 (dec (count phase-history)))
                      :dangling_run_count (config/dangling-run-count table)}}
      :signature signature-stub}
      (seq source-hashes) (assoc :source_hashes (vec source-hashes)))))
