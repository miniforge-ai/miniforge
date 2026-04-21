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

(def acquisition-classes
  "Four-layer acquisition taxonomy for metric sourcing."
  [:public_canonical :official_market :premium_benchmark :house_factor])

(def AcquisitionClass
  (into [:enum] acquisition-classes))

(def implementation-modes
  [:pull :derive :vendor :deprecated])

(def ImplementationMode
  (into [:enum] implementation-modes))

(def refresh-cadences
  [:realtime :daily :weekly :monthly :quarterly :annual])

(def RefreshCadence
  (into [:enum] refresh-cadences))

(def redistribution-risks
  [:none :low :medium :high])

(def RedistributionRisk
  (into [:enum] redistribution-risks))

(def metric-directions
  [:normal :inverse])

(def MetricDirection
  (into [:enum] metric-directions))

(def metric-units
  [:percent :ratio :index :basis-points :count :usd :level])

(def MetricUnit
  (into [:enum] metric-units))

;------------------------------------------------------------------------------ Layer 1
;; Metric entry and family schemas

(def MetricEntry
  "Schema for an individual metric definition."
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

(def MetricFamily
  "Schema for a group of related metrics."
  [:map
   [:family/id keyword?]
   [:family/name string?]
   [:family/metrics [:vector MetricEntry]]])

;------------------------------------------------------------------------------ Layer 2
;; Registry schema

(def MetricRegistry
  "Schema for the complete metric registry."
  [:map
   [:registry/id string?]
   [:registry/version string?]
   [:registry/families [:vector MetricFamily]]
   [:registry/pipeline-metric-map {:optional true} [:map-of string? [:vector string?]]]])

;------------------------------------------------------------------------------ Layer 2
;; Validation helpers

(defn valid?
  [schema value]
  (m/validate schema value))

(defn validate
  "Validate value against schema. Returns {:valid? bool :errors map-or-nil}."
  [schema value]
  (if (m/validate schema value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

(defn explain
  [schema value]
  (when-let [explanation (m/explain schema value)]
    (me/humanize explanation)))

(defn valid-metric?
  [value]
  (valid? MetricEntry value))

(defn valid-family?
  [value]
  (valid? MetricFamily value))

(defn valid-registry?
  [value]
  (valid? MetricRegistry value))

(defn validate-registry
  [value]
  (validate MetricRegistry value))
