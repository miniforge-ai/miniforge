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
(ns ai.miniforge.metric-registry.lookup
  "Pure query functions over metric registry data.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} all-metrics
  [registry]
  (mapcat :family/metrics (:registry/families registry)))

(defn ^{:stratum 0} find-metrics-by-family
  [registry family-id]
  (some (fn [f] (when (= family-id (:family/id f)) (:family/metrics f)))
        (:registry/families registry)))

(defn ^{:stratum 0} find-metrics-by-pipeline
  [registry pipeline-name]
  (get-in registry [:registry/pipeline-metric-map pipeline-name]))

(defn ^{:stratum 0} family-ids
  [registry]
  (mapv :family/id (:registry/families registry)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} find-metric
  [registry metric-id]
  (some (fn [m] (when (= metric-id (:metric/id m)) m))
        (all-metrics registry)))

(defn ^{:stratum 1} find-metrics-by-source-type
  [registry source-type]
  (filter #(= source-type (:metric/source-type %))
          (all-metrics registry)))

(defn ^{:stratum 1} metric-count
  [registry]
  (count (all-metrics registry)))
