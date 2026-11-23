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
(ns ai.miniforge.pipeline-config.resolver
  "Symbolic ref resolution for pipeline EDN configs.
   Pure function: takes EDN config + resolution context, returns resolved map."
  (:require [ai.miniforge.pipeline-config.rule-resolution :as rules])
  (:import [java.util UUID]
           [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resolve-stage-config
  "Resolve quality pack rules within stage config, if present."
  [rule-registry config]
  (if-let [pack (:stage/quality-pack config)]
    (assoc config :stage/quality-pack (rules/resolve-quality-pack rule-registry pack))
    config))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-pipeline
  "Resolve an EDN pipeline config into a runnable pipeline definition.
   Assigns UUIDs to stages, resolves symbolic connector/dataset refs,
   resolves stage dependencies by name→id, and hydrates quality rules.

   resolution-context keys:
     :connector-refs  — {keyword → UUID} map
     :dataset-refs    — {keyword → UUID} map
     :rule-registry   — {keyword → rule-fn-or-resolver} map
     :stage-configs   — {stage-name → config-map} map"
  [edn-config {:keys [connector-refs dataset-refs rule-registry
                       stage-configs]}]
  (let [;; Assign UUIDs to each stage
        stages-with-ids (mapv #(assoc % :stage/id (UUID/randomUUID))
                              (:pipeline/stages edn-config))

        ;; Build name→id index for dependency resolution
        name->id (into {} (map (juxt :stage/name :stage/id)) stages-with-ids)

        ;; Resolve all symbolic refs within each stage
        resolved-stages
        (mapv (fn [stage]
                (cond-> stage
                  ;; Resolve connector-ref keyword → UUID
                  (:stage/connector-ref stage)
                  (update :stage/connector-ref #(get connector-refs % %))

                  ;; Resolve dataset keywords → UUIDs
                  true
                  (update :stage/input-datasets
                          #(mapv (fn [ds] (get dataset-refs ds ds)) %))

                  true
                  (update :stage/output-datasets
                          #(mapv (fn [ds] (get dataset-refs ds ds)) %))

                  ;; Resolve dependencies from stage names → stage IDs
                  true
                  (update :stage/dependencies
                          #(mapv (fn [dep-name] (get name->id dep-name)) %))

                  ;; Merge env-specific stage config (file paths, etc.)
                  (get stage-configs (:stage/name stage))
                  (update :stage/config merge (get stage-configs (:stage/name stage)))

                  ;; Resolve quality rules in config
                  (:stage/config stage)
                  (update :stage/config #(resolve-stage-config rule-registry %))))
              stages-with-ids)

        now (Instant/now)]
    (assoc edn-config
           :pipeline/id         (UUID/randomUUID)
           :pipeline/stages     resolved-stages
           :pipeline/created-at now
           :pipeline/updated-at now)))
