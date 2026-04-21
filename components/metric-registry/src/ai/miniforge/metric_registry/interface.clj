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

;; -- Schema re-exports --
(def AcquisitionClass reg-schema/AcquisitionClass)
(def ImplementationMode reg-schema/ImplementationMode)
(def RefreshCadence reg-schema/RefreshCadence)
(def RedistributionRisk reg-schema/RedistributionRisk)
(def MetricDirection reg-schema/MetricDirection)
(def MetricUnit reg-schema/MetricUnit)
(def MetricEntry reg-schema/MetricEntry)
(def MetricFamily reg-schema/MetricFamily)
(def MetricRegistry reg-schema/MetricRegistry)

;; -- Enum value sets --
(def acquisition-classes reg-schema/acquisition-classes)
(def implementation-modes reg-schema/implementation-modes)
(def refresh-cadences reg-schema/refresh-cadences)
(def redistribution-risks reg-schema/redistribution-risks)
(def metric-directions reg-schema/metric-directions)
(def metric-units reg-schema/metric-units)

;; -- Validation --
(def valid-metric? reg-schema/valid-metric?)
(def valid-family? reg-schema/valid-family?)
(def valid-registry? reg-schema/valid-registry?)
(def validate-registry reg-schema/validate-registry)

;; -- Loading --
(defn load-registry
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
(defn all-metrics
  "Return flat sequence of all metrics across all families."
  [registry]
  (lookup/all-metrics registry))

(defn find-metric
  "Find a single metric by id. Returns nil if not found."
  [registry metric-id]
  (lookup/find-metric registry metric-id))

(defn find-metrics-by-family
  "Return all metrics in a given family."
  [registry family-id]
  (lookup/find-metrics-by-family registry family-id))

(defn find-metrics-by-source-type
  "Return all metrics matching a given acquisition class."
  [registry source-type]
  (lookup/find-metrics-by-source-type registry source-type))

(defn find-metrics-by-pipeline
  "Return metric ids mapped to a given pipeline name."
  [registry pipeline-name]
  (lookup/find-metrics-by-pipeline registry pipeline-name))

(defn family-ids
  "Return all family ids in the registry."
  [registry]
  (lookup/family-ids registry))

(defn metric-count
  "Return total number of metrics across all families."
  [registry]
  (lookup/metric-count registry))
