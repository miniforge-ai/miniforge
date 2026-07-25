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
(def ^{:stratum 0} SpecPayload
  "Malli `[:map ...]` schema for a normalized spec payload — the canonical
   :spec/* format the workflow engine consumes. Required (non-optional) keys:
   :spec/title, :spec/description, :spec/intent, :spec/constraints, :spec/tags,
   :spec/workflow-version, :spec/raw-data — normalize-spec supplies defaults for
   the ones the user omits. Remaining :spec/* keys (e.g. :spec/workflow-type,
   :spec/acceptance-criteria, :spec/provenance) are optional. Pass to malli.core
   fns or `valid-spec-payload?`."
  schema/SpecPayload)

(def ^{:stratum 0} SpecInput
  "Malli `[:map ...]` schema for raw user spec input in :spec/* format,
   before normalization. Required keys :spec/title and :spec/description;
   all other :spec/* keys plus :workflow/type and :workflow/version are
   optional. Pass to malli.core fns or `valid-spec-input?`."
  schema/SpecInput)

(def ^{:stratum 0} SpecIntent
  "Malli `[:map ...]` schema for :spec/intent — the kind of work. Requires
   :type (one of `intent-types`); optional :scope is a vector of strings."
  schema/SpecIntent)

(def ^{:stratum 0} SpecProvenance
  "Malli `[:map ...]` schema for :spec/provenance source metadata: requires
   :source-file (string), :source-format (one of `source-formats`),
   :loaded-at (inst), :file-size (int). Attached by `parse-spec-file`."
  schema/SpecProvenance)

(def ^{:stratum 0} PlanTask
  "Malli `[:map ...]` schema for one task within :spec/plan-tasks. Requires
   :task/id (keyword or uuid) and :task/description (string); optional
   :task/type (keyword) and :task/dependencies (vector of keyword/uuid)."
  schema/PlanTask)

(def ^{:stratum 0} CodeArtifact
  "Malli `[:map ...]` schema for a code artifact reference. Requires
   :code/id (string or uuid); optional :code/files is a vector of strings."
  schema/CodeArtifact)

;; Enum values
(def ^{:stratum 0} intent-types
  "Vector of valid :spec/intent :type keywords:
   [:refactor :feature :bugfix :general :chore :docs :test :performance]."
  schema/intent-types)

(def ^{:stratum 0} source-formats
  "Vector of supported spec file format keywords:
   [:yaml :edn :json :markdown]."
  schema/source-formats)

;; Parsing
(defn ^{:stratum 0} parse-spec-file
  "Parse a workflow specification file and normalize to canonical :spec/* format.

   Supported formats: .edn, .json, .md, .yaml/.yml (coming soon)

   Returns normalized spec map ready for workflow engine."
  [path]
  (core/parse-spec-file path))

(defn ^{:stratum 0} parse-content
  "Parse raw content string using the specified format parser.
   Returns the raw parsed data (not yet normalized)."
  [format content]
  (core/parse-content format content))

(defn ^{:stratum 0} detect-format
  "Detect file format from path extension."
  [path]
  (core/detect-format path))

;; Normalization
(defn ^{:stratum 0} normalize-spec
  "Normalize a parsed spec map to canonical :spec/* format.
   Applies defaults for missing optional fields."
  [spec]
  (core/normalize-spec spec))

;; Validation
(defn ^{:stratum 0} validate-spec
  "Validate a normalized spec against the Malli SpecPayload schema.

   Returns:
   - {:valid? true} if valid
   - {:valid? false :errors [...]} if invalid"
  [spec]
  (core/validate-spec spec))

(defn ^{:stratum 0} valid-spec-input?
  "Returns true if value is a valid SpecInput (pre-normalization)."
  [value]
  (schema/valid-spec-input? value))

(defn ^{:stratum 0} valid-spec-payload?
  "Returns true if value is a valid normalized SpecPayload."
  [value]
  (schema/valid-spec-payload? value))

(defn ^{:stratum 0} explain-spec-input
  "Returns human-readable explanation of input validation errors, or nil if valid."
  [value]
  (schema/explain-spec-input value))

(defn ^{:stratum 0} explain-spec-payload
  "Returns human-readable explanation of payload validation errors, or nil if valid."
  [value]
  (schema/explain-spec-payload value))
