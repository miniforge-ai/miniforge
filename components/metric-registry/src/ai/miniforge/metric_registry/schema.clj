(ns ai.miniforge.metric-registry.schema
  "Malli schemas for metric registry.

   Layer 0: Enums and base types
   Layer 1: Metric entry and family schemas
   Layer 2: Registry schema and validation helpers"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

;; Enums and base types
(def ^{:stratum 0} acquisition-classes
  "Four-layer acquisition taxonomy for metric sourcing."
  [:public_canonical :official_market :premium_benchmark :house_factor])

(def ^{:stratum 0} implementation-modes
  [:pull :derive :vendor :deprecated])

(def ^{:stratum 0} refresh-cadences
  [:realtime :daily :weekly :monthly :quarterly :annual])

(def ^{:stratum 0} redistribution-risks
  [:none :low :medium :high])

(def ^{:stratum 0} metric-directions
  [:normal :inverse])

(def ^{:stratum 0} metric-units
  [:percent :ratio :index :basis-points :count :usd :level])

;; Validation helpers
(defn ^{:stratum 0} valid?
  [schema value]
  (m/validate schema value))

(defn ^{:stratum 0} validate
  "Validate value against schema. Returns {:valid? bool :errors map-or-nil}."
  [schema value]
  (if (m/validate schema value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

(defn ^{:stratum 0} explain
  [schema value]
  (when-let [explanation (m/explain schema value)]
    (me/humanize explanation)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} AcquisitionClass
  (into [:enum] acquisition-classes))

(def ^{:stratum 1} ImplementationMode
  (into [:enum] implementation-modes))

(def ^{:stratum 1} RefreshCadence
  (into [:enum] refresh-cadences))

(def ^{:stratum 1} RedistributionRisk
  (into [:enum] redistribution-risks))

(def ^{:stratum 1} MetricDirection
  (into [:enum] metric-directions))

(def ^{:stratum 1} MetricUnit
  (into [:enum] metric-units))

;------------------------------------------------------------------------------ Layer 2

;; Metric entry and family schemas
(def ^{:stratum 2} MetricEntry
  [:map
   [:metric/id string?]
   [:metric/name string?]
   [:metric/source-type AcquisitionClass]
   [:metric/source-system string?]
   [:metric/refresh-cadence RefreshCadence]
   [:metric/implementation-mode ImplementationMode]
   [:metric/license-required boolean?]
   [:metric/redistribution-risk RedistributionRisk]
   [:metric/direction MetricDirection]
   [:metric/unit MetricUnit]
   [:metric/description {:optional true} string?]
   [:metric/series-id {:optional true} string?]])

;------------------------------------------------------------------------------ Layer 3

(def ^{:stratum 3} MetricFamily
  [:map
   [:family/id keyword?]
   [:family/name string?]
   [:family/metrics [:vector MetricEntry]]])

(defn ^{:stratum 3} valid-metric?
  [value]
  (valid? MetricEntry value))

;------------------------------------------------------------------------------ Layer 4

;; Registry schema
(def ^{:stratum 4} MetricRegistry
  [:map
   [:registry/id string?]
   [:registry/version string?]
   [:registry/families [:vector MetricFamily]]
   [:registry/pipeline-metric-map {:optional true} [:map-of string? [:vector string?]]]])

(defn ^{:stratum 4} valid-family?
  [value]
  (valid? MetricFamily value))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} valid-registry?
  [value]
  (valid? MetricRegistry value))

(defn ^{:stratum 5} validate-registry
  [value]
  (validate MetricRegistry value))
