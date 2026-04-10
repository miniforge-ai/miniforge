(ns ai.miniforge.data-foundry.pipeline-pack.schema
  "Malli schemas for pipeline pack manifests.

   Layer 0: Enums (TrustLevel, AuthorityChannel)
   Layer 1: PackManifest schema
   Layer 2: Validation helpers"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Enums

(def trust-levels
  [:tainted :untrusted :trusted])

(def TrustLevel
  (into [:enum] trust-levels))

(def authority-channels
  [:authority/data :authority/instruction])

(def AuthorityChannel
  (into [:enum] authority-channels))

;------------------------------------------------------------------------------ Layer 1
;; Pack manifest schema

(def PipelinePackManifest
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

;------------------------------------------------------------------------------ Layer 2
;; Validation helpers

(defn valid?
  [schema value]
  (m/validate schema value))

(defn validate
  [schema value]
  (if (m/validate schema value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

(defn explain
  [schema value]
  (when-let [explanation (m/explain schema value)]
    (me/humanize explanation)))

(defn valid-manifest?
  [value]
  (valid? PipelinePackManifest value))

(defn validate-manifest
  [value]
  (validate PipelinePackManifest value))
