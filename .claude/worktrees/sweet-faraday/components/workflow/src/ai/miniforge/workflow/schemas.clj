(ns ai.miniforge.workflow.schemas
  "Malli schemas for workflow-related data structures."
  (:require [malli.core :as m]
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
