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
(ns ai.miniforge.metric-registry.interface
  "Public API for the metric-registry component.

   Provides schema validation and pure query functions over metric registries.
   Registries are plain data (EDN maps) — this component has no state."
  (:require
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.metric-registry.schema :as reg-schema]
   [ai.miniforge.metric-registry.lookup :as lookup]
   [ai.miniforge.metric-registry.messages :as msg]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

;; -- Schema re-exports --
(def ^{:stratum 0} AcquisitionClass
  "Malli `[:enum ...]` schema for a metric's acquisition class (sourcing tier)."
  reg-schema/AcquisitionClass)

(def ^{:stratum 0} ImplementationMode
  "Malli `[:enum ...]` schema for how a metric is implemented (pull/derive/vendor/deprecated)."
  reg-schema/ImplementationMode)

(def ^{:stratum 0} RefreshCadence
  "Malli `[:enum ...]` schema for a metric's refresh cadence (realtime through annual)."
  reg-schema/RefreshCadence)

(def ^{:stratum 0} RedistributionRisk
  "Malli `[:enum ...]` schema for a metric's redistribution-risk level (none/low/medium/high)."
  reg-schema/RedistributionRisk)

(def ^{:stratum 0} MetricDirection
  "Malli `[:enum ...]` schema for a metric's directional polarity (normal/inverse)."
  reg-schema/MetricDirection)

(def ^{:stratum 0} MetricUnit
  "Malli `[:enum ...]` schema for a metric's unit (percent/ratio/index/basis-points/count/usd/level)."
  reg-schema/MetricUnit)

(def ^{:stratum 0} MetricEntry
  "Malli `[:map ...]` schema for one metric definition (id, name, sourcing, cadence, unit, etc.)."
  reg-schema/MetricEntry)

(def ^{:stratum 0} MetricFamily
  "Malli `[:map ...]` schema for a named group of related metrics."
  reg-schema/MetricFamily)

(def ^{:stratum 0} MetricRegistry
  "Malli `[:map ...]` schema for the complete registry (id, version, families, pipeline map)."
  reg-schema/MetricRegistry)

;; -- Enum value sets --
(def ^{:stratum 0} acquisition-classes
  "Vector of the four acquisition-class keywords, in taxonomy order."
  reg-schema/acquisition-classes)

(def ^{:stratum 0} implementation-modes
  "Vector of the implementation-mode keywords."
  reg-schema/implementation-modes)

(def ^{:stratum 0} refresh-cadences
  "Vector of the refresh-cadence keywords, ordered fastest to slowest."
  reg-schema/refresh-cadences)

(def ^{:stratum 0} redistribution-risks
  "Vector of the redistribution-risk keywords ([:none :low :medium :high]), in ascending order of risk."
  reg-schema/redistribution-risks)

(def ^{:stratum 0} metric-directions
  "Vector of the metric-direction keywords."
  reg-schema/metric-directions)

(def ^{:stratum 0} metric-units
  "Vector of the metric-unit keywords."
  reg-schema/metric-units)

;; -- Validation --
(def ^{:stratum 0} valid-metric?
  "Predicate: true if value conforms to the MetricEntry schema, false otherwise. Takes one metric value."
  reg-schema/valid-metric?)

(def ^{:stratum 0} valid-family?
  "Predicate: true if value conforms to the MetricFamily schema, false otherwise. Takes one family value."
  reg-schema/valid-family?)

(def ^{:stratum 0} valid-registry?
  "Predicate: true if value conforms to the MetricRegistry schema, false otherwise. Takes one registry value."
  reg-schema/valid-registry?)

(def ^{:stratum 0} validate-registry
  "Validate a registry value against MetricRegistry. Returns {:valid? bool :errors map-or-nil}; never throws on invalid input."
  reg-schema/validate-registry)

;; -- Loading --
(defn ^{:stratum 0} load-registry
  "Load a metric registry from an EDN file path.
   Returns {:success? true :registry ...} or {:success? false :error ...}"
  [path]
  (try
    (let [source (io/file path)]
      (if (.exists source)
        (let [content (slurp source)
              registry (edn/read-string content)
              validation (reg-schema/validate-registry registry)]
          (if (:valid? validation)
            (schema/success :registry registry)
            (schema/failure :registry (msg/t :registry/invalid {:errors (:errors validation)}))))
        (schema/failure :registry (msg/t :registry/file-not-found {:path path}))))
    (catch Exception e
      (schema/exception-failure :registry e))))

;; -- Lookups --
(defn ^{:stratum 0} all-metrics
  "Return flat sequence of all metrics across all families."
  [registry]
  (lookup/all-metrics registry))

(defn ^{:stratum 0} find-metric
  "Find a single metric by id. Returns nil if not found."
  [registry metric-id]
  (lookup/find-metric registry metric-id))

(defn ^{:stratum 0} find-metrics-by-family
  "Return all metrics in a given family."
  [registry family-id]
  (lookup/find-metrics-by-family registry family-id))

(defn ^{:stratum 0} find-metrics-by-source-type
  "Return all metrics matching a given acquisition class."
  [registry source-type]
  (lookup/find-metrics-by-source-type registry source-type))

(defn ^{:stratum 0} find-metrics-by-pipeline
  "Return metric ids mapped to a given pipeline name."
  [registry pipeline-name]
  (lookup/find-metrics-by-pipeline registry pipeline-name))

(defn ^{:stratum 0} family-ids
  "Return all family ids in the registry."
  [registry]
  (lookup/family-ids registry))

(defn ^{:stratum 0} metric-count
  "Return total number of metrics across all families."
  [registry]
  (lookup/metric-count registry))
