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

(ns ai.miniforge.connector-sarif.schema
  "Malli schemas, validation, and JSON Schema export for the SARIF
   connector contract."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as json-schema]))

;; -------------------------------------------------------------------------- Layer 0
;; Enums

(def SeverityLevel
  "Normalized severity levels."
  [:enum :error :warning :note :none])

(def SchemaType
  "Available extraction schemas."
  [:enum :sarif-result :sarif-run :csv-violation])

(def SourceFormat
  "Supported input file formats."
  [:enum :sarif :csv :auto])

;; -------------------------------------------------------------------------- Layer 1
;; Core schemas

(def Location
  "Physical location of a violation."
  [:map
   [:file {:optional true} [:maybe :string]]
   [:line {:optional true} [:maybe :int]]
   [:column {:optional true} [:maybe :int]]])

(def SarifViolation
  "Unified violation record — normalized from both SARIF and CSV sources."
  [:map
   [:violation/id :string]
   [:violation/rule-id :string]
   [:violation/message :string]
   [:violation/severity SeverityLevel]
   [:violation/location Location]
   [:violation/source-tool :string]
   [:violation/raw :map]])

(def SarifConfig
  "Configuration for the SARIF connector."
  [:map
   [:sarif/source-path :string]
   [:sarif/format {:optional true} SourceFormat]
   [:sarif/csv-columns {:optional true} [:map-of :keyword :string]]])

;; -------------------------------------------------------------------------- Layer 2
;; Validation

(defn validate
  "Validate value against schema. Returns {:valid? bool :errors map-or-nil}."
  [schema value]
  (if (m/validate schema value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

(defn validate-config
  "Validate a SARIF connector config map."
  [value]
  (validate SarifConfig value))

(defn validate-violation
  "Validate a single violation record."
  [value]
  (validate SarifViolation value))

;; -------------------------------------------------------------------------- Layer 3
;; JSON Schema export

(defn violation-json-schema
  "Return the SarifViolation schema as JSON Schema."
  []
  (json-schema/transform SarifViolation))

(defn config-json-schema
  "Return the SarifConfig schema as JSON Schema."
  []
  (json-schema/transform SarifConfig))
