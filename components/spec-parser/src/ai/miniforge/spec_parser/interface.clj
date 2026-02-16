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

(ns ai.miniforge.spec-parser.interface
  "Public API for the spec-parser component.

   Parses workflow specification files (EDN, JSON, Markdown) into
   canonical :spec/* format for the workflow engine."
  (:require
   [ai.miniforge.spec-parser.core :as core]
   [ai.miniforge.spec-parser.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def SpecPayload schema/SpecPayload)
(def SpecInput schema/SpecInput)
(def SpecIntent schema/SpecIntent)
(def SpecProvenance schema/SpecProvenance)
(def PlanTask schema/PlanTask)
(def CodeArtifact schema/CodeArtifact)

;; Enum values
(def intent-types schema/intent-types)
(def source-formats schema/source-formats)

;------------------------------------------------------------------------------ Layer 1
;; Parsing

(defn parse-spec-file
  "Parse a workflow specification file and normalize to canonical :spec/* format.

   Supported formats: .edn, .json, .md, .yaml/.yml (coming soon)

   Returns normalized spec map ready for workflow engine."
  [path]
  (core/parse-spec-file path))

(defn parse-content
  "Parse raw content string using the specified format parser.
   Returns the raw parsed data (not yet normalized)."
  [format content]
  (core/parse-content format content))

(defn detect-format
  "Detect file format from path extension."
  [path]
  (core/detect-format path))

;------------------------------------------------------------------------------ Layer 2
;; Normalization

(defn normalize-spec
  "Normalize a parsed spec map to canonical :spec/* format.
   Applies defaults for missing optional fields."
  [spec]
  (core/normalize-spec spec))

;------------------------------------------------------------------------------ Layer 3
;; Validation

(defn validate-spec
  "Validate a normalized spec against the Malli SpecPayload schema.

   Returns:
   - {:valid? true} if valid
   - {:valid? false :errors [...]} if invalid"
  [spec]
  (core/validate-spec spec))

(defn valid-spec-input?
  "Returns true if value is a valid SpecInput (pre-normalization)."
  [value]
  (schema/valid-spec-input? value))

(defn valid-spec-payload?
  "Returns true if value is a valid normalized SpecPayload."
  [value]
  (schema/valid-spec-payload? value))

(defn explain-spec-input
  "Returns human-readable explanation of input validation errors, or nil if valid."
  [value]
  (schema/explain-spec-input value))

(defn explain-spec-payload
  "Returns human-readable explanation of payload validation errors, or nil if valid."
  [value]
  (schema/explain-spec-payload value))
