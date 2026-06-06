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

(ns ai.miniforge.connector-pipeline-output.interface
  "Public API for the pipeline output connector.
   A generic file-based sink that writes pipeline results in a structured
   format (EDN default, JSON via format transform) for downstream consumers.

   Exposes Malli and JSON Schema definitions for the output contract so
   consumers can validate manifests and configure pipelines correctly."
  (:require [ai.miniforge.connector-pipeline-output.core :as core]
            [ai.miniforge.connector-pipeline-output.schema :as schema]
            [ai.miniforge.connector.interface :as conn]))

(defn create-pipeline-output-connector
  "Create a new PipelineOutputConnector instance."
  []
  (core/->PipelineOutputConnector))

(def connector-metadata
  "Registration metadata for the pipeline output connector."
  {:connector/name         "Pipeline Output Connector"
   :connector/type         :sink
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/batch}
   :connector/auth-methods #{:none}
   :connector/retry-policy (conn/retry-policy :none)
   :connector/maintainer   "data-foundry"})

;; -- Public Contract Schemas (Malli) --

(def Manifest
  "Malli schema for pipeline output manifest.
   Consumers use this to validate manifests read from the output store."
  schema/Manifest)

(def OutputConfig
  "Malli schema for pipeline output connector config.
   Pipeline packs use this to validate their output stage config."
  schema/OutputConfig)

;; -- Public Contract Schemas (JSON Schema) --

(defn manifest-json-schema
  "Return the Manifest as JSON Schema for non-Clojure consumers."
  []
  (schema/manifest-json-schema))

(defn config-json-schema
  "Return the OutputConfig as JSON Schema for non-Clojure consumers."
  []
  (schema/config-json-schema))

;; -- Validation --

(defn validate-manifest
  "Validate a manifest map against the Manifest schema.
   Returns {:valid? bool :errors map-or-nil}."
  [manifest]
  (schema/validate-manifest manifest))

(defn validate-config
  "Validate a config map against the OutputConfig schema.
   Returns {:valid? bool :errors map-or-nil}."
  [config]
  (schema/validate-config config))
