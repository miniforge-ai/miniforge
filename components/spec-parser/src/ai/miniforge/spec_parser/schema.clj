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

(ns ai.miniforge.spec-parser.schema
  "Malli schemas for workflow spec payloads.
   Layer 0: Enums and base types
   Layer 1: Composite SpecPayload schema
   Layer 2: Validation helpers"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Enums and base types

(def intent-types
  "Valid spec intent types."
  [:refactor :feature :bugfix :general :chore :docs :test :performance])

(def source-formats
  "Supported spec file formats."
  [:yaml :edn :json :markdown])

;------------------------------------------------------------------------------ Layer 1
;; Composite schemas

(def SpecIntent
  "Schema for spec intent — what kind of work this is."
  [:map
   [:type (into [:enum] intent-types)]
   [:scope {:optional true} [:vector :string]]])

(def PlanTask
  "Schema for an individual task within :spec/plan-tasks."
  [:map
   [:task/id [:or :keyword :uuid]]
   [:task/description :string]
   [:task/type {:optional true} :keyword]
   [:task/dependencies {:optional true} [:vector [:or :keyword :uuid]]]])

(def CodeArtifact
  "Schema for a code artifact reference."
  [:map
   [:code/id [:or :string :uuid]]
   [:code/files {:optional true} [:vector :string]]])

(def SpecProvenance
  "Schema for spec source provenance metadata."
  [:map
   [:source-file :string]
   [:source-format (into [:enum] source-formats)]
   [:loaded-at inst?]
   [:file-size :int]])

(def SpecPayload
  "Schema for a normalized spec payload — the canonical format.

   All fields use the :spec/* namespace. Required: title, description.
   Everything else is optional with sensible defaults applied by normalize-spec."
  [:map
   ;; Required
   [:spec/title [:string {:min 1}]]
   [:spec/description [:string {:min 1}]]

   ;; Defaults applied
   [:spec/intent SpecIntent]
   [:spec/constraints [:vector :string]]
   [:spec/tags [:vector [:or :string :keyword]]]
   [:spec/workflow-type {:optional true} :keyword]
   [:spec/workflow-version :string]
   [:spec/raw-data :map]

   ;; Optional promoted keys
   [:spec/acceptance-criteria {:optional true} [:vector [:or :string :map]]]
   [:spec/code-artifact {:optional true} [:or CodeArtifact :map]]
   [:spec/repo-url {:optional true} [:string {:min 1}]]
   [:spec/branch {:optional true} [:string {:min 1}]]
   [:spec/llm-backend {:optional true} :keyword]
   [:spec/sandbox {:optional true} :boolean]
   [:spec/plan-tasks {:optional true} [:vector [:or PlanTask :map]]]

   ;; Added by parse-spec-file
   [:spec/provenance {:optional true} SpecProvenance]])

(def SpecInput
  "Schema for raw spec input — what the user writes in :spec/* format.
   This is the canonical input format before normalization."
  [:map
   ;; Required
   [:spec/title [:string {:min 1}]]
   [:spec/description [:string {:min 1}]]

   ;; Optional
   [:spec/intent {:optional true} SpecIntent]
   [:spec/constraints {:optional true} [:vector :string]]
   [:spec/tags {:optional true} [:vector [:or :string :keyword]]]
   [:spec/acceptance-criteria {:optional true} [:vector [:or :string :map]]]
   [:spec/code-artifact {:optional true} [:or CodeArtifact :map]]
   [:spec/repo-url {:optional true} [:string {:min 1}]]
   [:spec/branch {:optional true} [:string {:min 1}]]
   [:spec/llm-backend {:optional true} :keyword]
   [:spec/sandbox {:optional true} :boolean]
   [:spec/plan-tasks {:optional true} [:vector [:or PlanTask :map]]]

   ;; Workflow metadata (cross-domain, kept as :workflow/*)
   [:workflow/type {:optional true} :keyword]
   [:workflow/version {:optional true} :string]])

;------------------------------------------------------------------------------ Layer 2
;; Validation helpers

(defn valid-spec-input?
  "Returns true if value is a valid SpecInput."
  [value]
  (m/validate SpecInput value))

(defn valid-spec-payload?
  "Returns true if value is a valid normalized SpecPayload."
  [value]
  (m/validate SpecPayload value))

(defn explain-spec-input
  "Returns human-readable explanation of input validation errors, or nil if valid."
  [value]
  (when-let [explanation (m/explain SpecInput value)]
    (me/humanize explanation)))

(defn explain-spec-payload
  "Returns human-readable explanation of payload validation errors, or nil if valid."
  [value]
  (when-let [explanation (m/explain SpecPayload value)]
    (me/humanize explanation)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate spec input
  (valid-spec-input? {:spec/title "Refactor logging"
                      :spec/description "Extract logging to separate module"
                      :spec/intent {:type :refactor :scope ["src/logging"]}})
  ;; => true

  ;; Explain invalid input
  (explain-spec-input {:spec/title ""})
  ;; => {:spec/title ["should be at least 1 characters"], :spec/description [...]}

  :leave-this-here)
