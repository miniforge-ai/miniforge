(ns ai.miniforge.data-foundry.connector-pipeline-output.schema
  "Malli schemas, validation, and JSON Schema export for the pipeline
   output contract. These schemas define the public API that downstream
   consumers depend on."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as json-schema]))

;; -------------------------------------------------------------------------- Layer 0
;; Enums

(def supported-formats #{:edn :json})

(def OutputFormat
  "Supported record output formats."
  [:enum :edn :json])

;; -------------------------------------------------------------------------- Layer 1
;; Schemas

(def OutputConfig
  "Config schema for pipeline output connector.
   :output/dir is required; all others have defaults."
  [:map
   [:output/dir [:string {:min 1}]]
   [:output/format {:optional true} OutputFormat]
   [:output/run-id {:optional true} [:string {:min 1}]]
   [:output/pipeline-name {:optional true} [:string {:min 1}]]])

(def Manifest
  "Schema for the pipeline output manifest file.
   This is the public contract — consumers read the manifest to discover
   what was produced and where."
  [:map
   [:manifest/version [:= "1.0"]]
   [:manifest/run-id [:string {:min 1}]]
   [:manifest/pipeline-name {:optional true} [:maybe [:string {:min 1}]]]
   [:manifest/format OutputFormat]
   [:manifest/record-count [:int {:min 0}]]
   [:manifest/schema-name {:optional true} [:maybe :string]]
   [:manifest/created-at [:string {:min 1}]]
   [:manifest/records-file [:string {:min 1}]]])

;; -------------------------------------------------------------------------- Layer 2
;; Validation

(defn validate
  "Validate value against schema.
   Returns {:valid? bool :errors map-or-nil}."
  [schema value]
  (if (m/validate schema value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

(defn validate!
  "Validate value against schema, throwing on failure."
  [schema value]
  (when-not (m/validate schema value)
    (throw (ex-info "Schema validation failed"
                    {:errors (me/humanize (m/explain schema value))
                     :value  value})))
  value)

(defn validate-config
  "Validate a pipeline output config map."
  [value]
  (validate OutputConfig value))

(defn validate-manifest
  "Validate a pipeline output manifest map."
  [value]
  (validate Manifest value))

;; -------------------------------------------------------------------------- Layer 3
;; JSON Schema export (for non-Clojure consumers)

(defn manifest-json-schema
  "Return the Manifest schema as JSON Schema (for external consumers).
   Write this to the output directory so non-Clojure clients can validate."
  []
  (json-schema/transform Manifest))

(defn config-json-schema
  "Return the OutputConfig schema as JSON Schema."
  []
  (json-schema/transform OutputConfig))
