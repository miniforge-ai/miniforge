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

(ns ai.miniforge.workflow.schemas
  "Malli schemas for workflow-related data structures."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [malli.core :as m]
            [malli.error :as me]))

;;------------------------------------------------------------------------------ Layer 0
;; Workflow characteristics schema

(def WorkflowComplexity
  "Workflow complexity level"
  [:enum :simple :medium :complex])

(def WorkflowCharacteristics
  "Schema for workflow characteristics returned by workflow-characteristics fn.

   Used for workflow selection and recommendation."
  [:map
   [:id keyword?]
   [:version string?]
   [:name string?]
   [:description string?]
   [:phases pos-int?]
   [:max-iterations pos-int?]
   [:task-types [:vector keyword?]]
   [:complexity WorkflowComplexity]
   [:has-review boolean?]
   [:has-testing boolean?]])

(def WorkflowRunId
  "Workflow run identifier persisted in checkpoint data."
  [:or uuid? string? keyword?])

(def CheckpointManifest
  "Durable checkpoint manifest persisted for a workflow run."
  [:map {:closed false}
   [:workflow/id WorkflowRunId]
   [:workflow/workflow-id [:maybe keyword?]]
   [:workflow/workflow-version [:maybe string?]]
   [:workflow/phases-completed [:vector keyword?]]
   [:workflow/machine-snapshot-path string?]
   [:workflow/phase-checkpoints [:map-of keyword? string?]]
   [:workflow/last-checkpoint-at string?]])

(def MachineSnapshot
  "Serializable execution snapshot persisted for workflow resume."
  [:map {:closed false}
   [:execution/id WorkflowRunId]
   [:execution/workflow-id [:maybe keyword?]]
   [:execution/workflow-version [:maybe string?]]
   [:execution/status keyword?]
   [:execution/fsm-state some?]
   [:execution/response-chain map?]
   [:execution/errors [:vector map?]]
   [:execution/artifacts [:vector any?]]
   [:execution/metrics map?]])

(def PhaseResults
  "Checkpointed phase results keyed by phase id."
  [:map-of keyword? map?])

(def CheckpointData
  "Loaded checkpoint payload returned from the workflow checkpoint boundary."
  [:map {:closed false}
   [:checkpoint/root string?]
   [:manifest CheckpointManifest]
   [:machine-snapshot MachineSnapshot]
   [:phase-results PhaseResults]])

;;------------------------------------------------------------------------------ Layer 1
;; Workflow recommendation schema

(def WorkflowRecommendation
  "Schema for workflow recommendation results.

   Represents the output of workflow recommendation logic,
   either from rules-based or LLM-based recommendation."
  [:map
   [:workflow [:maybe keyword?]]
   [:confidence [:double {:min 0.0 :max 1.0}]]
   [:reasoning string?]
   [:source [:enum :rules :llm]]
   [:characteristics {:optional true} [:vector string?]]])

;;------------------------------------------------------------------------------ Layer 2
;; Validation functions

(defn valid-characteristics?
  "Check if a value is valid workflow characteristics.

   Arguments:
     value - Value to validate

   Returns: Boolean"
  [value]
  (m/validate WorkflowCharacteristics value))

(defn valid-recommendation?
  "Check if a value is a valid workflow recommendation.

   Arguments:
     value - Value to validate

   Returns: Boolean"
  [value]
  (m/validate WorkflowRecommendation value))

(defn valid-checkpoint-data?
  "Check if a value is valid loaded checkpoint data."
  [value]
  (m/validate CheckpointData value))

(defn explain-characteristics
  "Get human-readable explanation of validation errors for characteristics.

   Arguments:
     value - Value to validate

   Returns: Error explanation or nil if valid"
  [value]
  (when-let [explanation (m/explain WorkflowCharacteristics value)]
    (me/humanize explanation)))

(defn explain-recommendation
  "Get human-readable explanation of validation errors for recommendation.

   Arguments:
     value - Value to validate

   Returns: Error explanation or nil if valid"
  [value]
  (when-let [explanation (m/explain WorkflowRecommendation value)]
    (me/humanize explanation)))

(defn explain-checkpoint-data
  "Get human-readable explanation of validation errors for checkpoint data."
  [value]
  (when-let [explanation (m/explain CheckpointData value)]
    (me/humanize explanation)))

(defn validate-checkpoint-data
  "Validate loaded or soon-to-be-persisted checkpoint data.

   Returns:
   - the input value when it satisfies `CheckpointData`
   - an `:invalid-input` anomaly carrying `:value` and `:errors`
     (humanized malli explanation) otherwise

   This is the canonical, anomaly-returning entry point. The boundary
   helper `validate-checkpoint-data!` re-escalates the anomaly via a
   thrown `ex-info` for callers (e.g. the checkpoint store) that
   treat schema breakage as a programmer error rather than a runtime
   condition to be carried forward as data."
  [value]
  (if (valid-checkpoint-data? value)
    value
    (anomaly/anomaly :invalid-input
                     "Invalid checkpoint data"
                     {:value value
                      :errors (explain-checkpoint-data value)})))

(defn validate-checkpoint-data!
  "Validate loaded or soon-to-be-persisted checkpoint data at the boundary.

   Returns the input value when valid; throws `ex-info` carrying
   `:value` and `:errors` when invalid. Use `validate-checkpoint-data`
   for an anomaly-returning equivalent."
  [value]
  (let [result (validate-checkpoint-data value)]
    (if (anomaly/anomaly? result)
      (throw (ex-info (:anomaly/message result)
                      (:anomaly/data result)))
      result)))
