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

(ns ai.miniforge.workbench-etl-adapter.core
  "Pure assembly of a Miniforge ETL workbench_snapshot/v1 wire map."
  (:require
   [ai.miniforge.workbench-etl-adapter.evaluation :as evaluation]))

;------------------------------------------------------------------------------ Layer 0

(def ^:private snapshot-schema-version
  "Canonical wire-contract version emitted by this adapter."
  "workbench_snapshot/v1")

(def ^:private signature-stub
  "Unsigned contract placeholder required until product snapshot signing lands."
  {:algorithm "sha256"
   :digest "0000000000000000000000000000000000000000000000000000000000000000"
   :canonicalization "json-c14n/1"})

(defn- factor-axes [inventory changed-factor]
  (cond-> {:resolved_config_hash (:config_hash inventory)
           :factor_count (str (:factor_count inventory))}
    changed-factor
    (assoc :factor_id (:id changed-factor)
           :baseline_value (:baseline changed-factor)
           :candidate_value (:candidate changed-factor))))

(defn- variant [resolved-run-config inventory changed-factor experiment-id label]
  (let [pipeline (:pipeline resolved-run-config)]
    {:experiment_id experiment-id
     :label label
     :workflow {:id (:pipeline/name pipeline)
                :version (:pipeline/version pipeline)}
     :method (name :etl)
     :axes (factor-axes inventory changed-factor)}))

;------------------------------------------------------------------------------ Layer 1

(defn snapshot
  "Project a pipeline result and resolved-run inventory into a snapshot."
  [registry result resolved-run-config inventory changed-factor
   {:keys [experiment-id label snapshot-id run-id source-hashes]}]
  (let [run          (:pipeline-run result)
        stage-runs   (vec (:pipeline-run/stage-runs run))
        configured-stage-count
        (count (get-in resolved-run-config [:pipeline :pipeline/stages]))
        evaluated-at (str (or (:pipeline-run/completed-at run)
                              (:pipeline-run/created-at run)))
        run-id       (or run-id (str (:pipeline-run/id run)))
        snapshot-id  (or snapshot-id (str "wb-" run-id))]
    (cond->
     {:schema_version snapshot-schema-version
      :snapshot_id snapshot-id
      :generated_at evaluated-at
      :product (:product registry)
      :run_id run-id
      :variant (variant resolved-run-config inventory changed-factor
                        experiment-id label)
      :registry_ref {:registry_id (:registry_id registry)
                     :version (:version registry)}
      :evaluations (evaluation/evaluations registry run stage-runs
                                           configured-stage-count evaluated-at)
      :entities
      {:miniforge.etl_run
       {:pipeline_name (get-in resolved-run-config [:pipeline :pipeline/name])
        :pipeline_version (get-in resolved-run-config [:pipeline :pipeline/version])
        :pipeline_mode (some-> (get-in resolved-run-config [:pipeline :pipeline/mode]) name)
        :configured_stage_count configured-stage-count
        :executed_stage_count (count stage-runs)
        :configuration_hash (:config_hash inventory)
        :factor_count (:factor_count inventory)
        :redacted_count (:redacted_count inventory)}}
      :metadata
      {:provenance (evaluation/provenance registry)
       :resolved_run {:schema_version (:schema_version inventory)
                      :domain (name :etl)
                      :configuration_hash (:config_hash inventory)
                      :factor_count (:factor_count inventory)
                      :redacted_count (:redacted_count inventory)
                      :factors (:values inventory)
                      :change changed-factor}}
      :signature signature-stub}
      (seq source-hashes) (assoc :source_hashes (vec source-hashes)))))
