(ns ai.miniforge.pipeline-pack.schema
  "Malli schemas for pipeline pack manifests.

   Layer 0: enum value vectors, plus schema-generic validation helpers
   Layer 1: TrustLevel/AuthorityChannel enum schemas (built from Layer 0's vectors)
   Layer 2: PipelinePackManifest
   Layer 3: valid-manifest?/validate-manifest (compose Layer 0's helpers with Layer 2's schema)"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

;; Enums
(def ^{:stratum 0} trust-levels
  [:tainted :untrusted :trusted])

(def ^{:stratum 0} authority-channels
  [:authority/data :authority/instruction])

;; Validation helpers
(defn ^{:stratum 0} valid?
  [schema value]
  (m/validate schema value))

(defn ^{:stratum 0} validate
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

(def ^{:stratum 1} TrustLevel
  (into [:enum] trust-levels))

(def ^{:stratum 1} AuthorityChannel
  (into [:enum] authority-channels))

;------------------------------------------------------------------------------ Layer 2

;; Pack manifest schema
(def ^{:stratum 2} PipelinePackManifest
  "Schema for a pipeline pack manifest.

   Trust model mirrors policy-pack:
   - :untrusted (default) — data-only packs, no code execution
   - :trusted — may use :authority/instruction (code-bearing packs)
   - :tainted — flagged, must not be used for instruction"
  [:map
   [:pack/id string?]
   [:pack/name string?]
   [:pack/version string?]
   [:pack/description string?]
   [:pack/author string?]
   [:pack/trust-level TrustLevel]
   [:pack/authority AuthorityChannel]
   [:pack/pipelines [:vector string?]]
   [:pack/envs [:vector string?]]
   [:pack/metric-registry {:optional true} string?]
   [:pack/connector-types {:optional true} [:set keyword?]]
   [:pack/extends {:optional true} [:vector [:map [:pack-id string?]]]]
   [:pack/created-at inst?]
   [:pack/updated-at inst?]])

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} valid-manifest?
  [value]
  (valid? PipelinePackManifest value))

(defn ^{:stratum 3} validate-manifest
  [value]
  (validate PipelinePackManifest value))
