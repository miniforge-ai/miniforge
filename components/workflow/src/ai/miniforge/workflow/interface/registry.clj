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

(ns ai.miniforge.workflow.interface.registry
  "Workflow registry and schema-adjacent helpers."
  (:require
   [ai.miniforge.workflow.registry :as registry]
   [ai.miniforge.workflow.schemas :as schemas]))

;------------------------------------------------------------------------------ Layer 0
;; Registry and schema helpers

(def register-workflow!
  "Validate and register a workflow definition. Args: [workflow]. Returns
   the registered workflow; throws an anomaly when :workflow/id is missing
   or validation fails."
  registry/register-workflow!)

(def get-workflow
  "Fetch a registered workflow by id. Args: [workflow-id]. Returns the
   workflow definition map, or nil when absent."
  registry/get-workflow)

(def list-workflow-ids
  "List ids of all registered workflows. No args. Returns a seq of workflow
   id keywords."
  registry/list-workflow-ids)

(def workflow-exists?
  "Predicate: is a workflow id registered? Args: [workflow-id]. Returns
   boolean."
  registry/workflow-exists?)

(def workflow-characteristics
  "Derive selection characteristics from a workflow, validated against the
   WorkflowCharacteristics schema. Args: [workflow]. Returns
   {:id :version :name :description :phases :max-iterations :task-types
   :complexity (:simple|:medium|:complex) :has-review :has-testing}; throws
   an anomaly when the computed characteristics fail validation."
  registry/workflow-characteristics)

(def ensure-initialized!
  "Idempotently load workflows from resources into the registry. No args.
   Returns the count of workflows in the registry."
  registry/ensure-initialized!)

(def valid-recommendation?
  "Predicate: does a value satisfy the WorkflowRecommendation schema? Args:
   [value]. Returns boolean."
  schemas/valid-recommendation?)

(def explain-recommendation
  "Humanize WorkflowRecommendation schema errors for a value. Args: [value].
   Returns the humanized malli explanation, or nil when the value is valid."
  schemas/explain-recommendation)
