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

(ns ai.miniforge.schema.core
  "Core domain schemas for miniforge.
   Layer 0: Base types and registries
   Layer 1: Composite schemas (Agent, Task, Artifact, Workflow)"
  (:require
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Base types and registries

(def agent-roles
  "Canonical agent roles ordered by implementation priority."
  [:planner :architect :implementer :tester :reviewer :sre :security :release :historian :operator])

(def supervisor-roles
  "Supervisor roles for workflow monitoring and control."
  [:progress-monitor :test-quality :conflict-detector :resource-manager :evidence-collector])

(def task-types
  "Types of tasks that can be assigned to agents."
  [:plan :design :implement :test :review :deploy])

(def task-statuses
  "Possible states for a task."
  [:pending :running :completed :failed :blocked])

(def artifact-types
  "Types of artifacts produced by the system."
  [:spec :plan :adr :code :test :review :manifest :image :telemetry :incident])

(def workflow-phases
  "Phases in the outer loop SDLC cycle."
  [:plan :design :implement :verify :review :release :observe])

(def workflow-statuses
  "Possible states for a workflow."
  [:pending :running :paused :completed :failed :cancelled])

(def registry
  "Malli registry for base schema types."
  {;; Identifiers
   :id/uuid        uuid?
   :id/string      [:string {:min 1}]

   ;; Agent types
   :agent/id       :id/uuid
   :agent/role     (into [:enum] agent-roles)
   :agent/capability keyword?

   ;; Supervision types
   :supervisor/id  keyword?
   :supervisor/role (into [:enum] supervisor-roles)
   :supervisor/status [:enum :healthy :warning :halt]
   :supervisor/priority [:enum :high :medium :low]

   ;; Task types
   :task/id        :id/uuid
   :task/type      (into [:enum] task-types)
   :task/status    (into [:enum] task-statuses)

   ;; Artifact types
   :artifact/id    :id/uuid
   :artifact/type  (into [:enum] artifact-types)
   :artifact/version [:string {:min 1}]

   ;; Workflow types
   :workflow/id    :id/uuid
   :workflow/phase (into [:enum] workflow-phases)
   :workflow/status (into [:enum] workflow-statuses)

   ;; Common types
   :common/timestamp inst?
   :common/non-neg-int [:int {:min 0}]
   :common/pos-number [:double {:min 0.0}]})

;------------------------------------------------------------------------------ Layer 1
;; Composite schemas

(def Agent
  "Schema for an AI agent.
   Agents are pure functions: (context, task) -> (artifacts, decisions, signals)"
  [:map {:registry registry}
   [:agent/id :agent/id]
   [:agent/role :agent/role]
   [:agent/capabilities {:optional true} [:set :agent/capability]]
   [:agent/memory {:optional true} [:maybe :id/uuid]]
   [:agent/config {:optional true}
    [:map
     [:model {:optional true} :id/string]
     [:temperature {:optional true} [:double {:min 0.0 :max 2.0}]]
     [:max-tokens {:optional true} :common/non-neg-int]
     [:budget {:optional true}
      [:map
       [:tokens {:optional true} :common/non-neg-int]
       [:cost-usd {:optional true} :common/pos-number]]]]]])

(def TaskConstraints
  "Schema for task execution constraints."
  [:map {:registry registry}
   [:budget {:optional true}
    [:map
     [:tokens {:optional true} :common/non-neg-int]
     [:cost-usd {:optional true} :common/pos-number]
     [:duration-ms {:optional true} :common/non-neg-int]]]
   [:deadline {:optional true} :common/timestamp]
   [:policies {:optional true} [:vector keyword?]]
   [:max-iterations {:optional true} :common/non-neg-int]])

(def TaskResult
  "Schema for task execution result."
  [:map {:registry registry}
   [:outcome [:enum :success :failure :escalated]]
   [:error {:optional true} :id/string]
   [:signals {:optional true} [:vector keyword?]]
   [:metrics {:optional true}
    [:map
     [:duration-ms {:optional true} :common/non-neg-int]
     [:tokens-used {:optional true} :common/non-neg-int]
     [:cost-usd {:optional true} :common/pos-number]
     [:iterations {:optional true} :common/non-neg-int]]]])

(def Task
  "Schema for a unit of work assigned to an agent."
  [:map {:registry registry}
   [:task/id :task/id]
   [:task/type :task/type]
   [:task/status :task/status]
   [:task/agent {:optional true} :agent/id]
   [:task/inputs {:optional true} [:vector :artifact/id]]
   [:task/outputs {:optional true} [:vector :artifact/id]]
   [:task/parent {:optional true} [:maybe :task/id]]
   [:task/children {:optional true} [:vector :task/id]]
   [:task/constraints {:optional true} TaskConstraints]
   [:task/result {:optional true} TaskResult]])

(def ArtifactOrigin
  "Schema for artifact provenance origin."
  [:map {:registry registry}
   [:intent-id {:optional true} :id/uuid]
   [:agent-id {:optional true} :agent/id]
   [:task-id {:optional true} :task/id]])

(def Artifact
  "Schema for a versioned work product with provenance."
  [:map {:registry registry}
   [:artifact/id :artifact/id]
   [:artifact/type :artifact/type]
   [:artifact/version :artifact/version]
   [:artifact/content {:optional true} any?]
   [:artifact/origin {:optional true} ArtifactOrigin]
   [:artifact/parents {:optional true} [:vector :artifact/id]]
   [:artifact/children {:optional true} [:vector :artifact/id]]
   [:artifact/metadata {:optional true} [:map-of keyword? any?]]
   [:artifact/created-at {:optional true} :common/timestamp]])

(def WorkflowBudget
  "Schema for workflow budget allocation."
  [:map {:registry registry}
   [:tokens {:optional true} :common/non-neg-int]
   [:cost-usd {:optional true} :common/pos-number]
   [:duration-ms {:optional true} :common/non-neg-int]])

(def Workflow
  "Schema for an outer loop SDLC delivery instance."
  [:map {:registry registry}
   [:workflow/id :workflow/id]
   [:workflow/name {:optional true} :id/string]
   [:workflow/status :workflow/status]
   [:workflow/phase {:optional true} :workflow/phase]
   [:workflow/priority {:optional true} [:int {:min 0 :max 10}]]
   [:workflow/checkpoint {:optional true}
    [:map
     [:phase :workflow/phase]
     [:task-id {:optional true} :task/id]
     [:timestamp :common/timestamp]]]
   [:workflow/budget {:optional true} WorkflowBudget]
   [:workflow/consumed {:optional true} WorkflowBudget]
   [:workflow/spec-id {:optional true} :artifact/id]
   [:workflow/created-at {:optional true} :common/timestamp]
   [:workflow/supervisors {:optional true}
    [:vector
     [:map
      [:id :supervisor/id]
      [:enabled? {:optional true} boolean?]
      [:config {:optional true} [:map-of keyword? any?]]]]]])

(def SupervisorConfig
  "Schema for supervisor configuration."
  [:map {:registry registry}
   [:id :supervisor/id]
   [:name :id/string]
   [:can-halt? boolean?]
   [:check-interval-ms :common/non-neg-int]
   [:priority :supervisor/priority]
   [:enabled? boolean?]])

(def SupervisorHealthCheck
  "Schema for supervisor health check result."
  [:map {:registry registry}
   [:status :supervisor/status]
   [:agent/id :supervisor/id]
   [:message :id/string]
   [:data {:optional true} [:map-of keyword? any?]]
   [:checked-at :common/timestamp]])

(def SupervisionCoordinatorState
  "Schema for supervision coordinator state."
  [:map {:registry registry}
   [:status :supervisor/status]
   [:checks [:vector SupervisorHealthCheck]]
   [:halt-reason {:optional true} :id/string]
   [:halting-agent {:optional true} :supervisor/id]
   [:warnings {:optional true} [:vector :id/string]]
   [:checked-at :common/timestamp]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate Agent
  (m/validate Agent
              {:agent/id (random-uuid)
               :agent/role :implementer
               :agent/capabilities #{:code :test}
               :agent/config {:model "claude-sonnet-4"
                              :max-tokens 8000}})
  ;; => true

  ;; Validate Task
  (m/validate Task
              {:task/id (random-uuid)
               :task/type :implement
               :task/status :pending
               :task/constraints {:budget {:tokens 50000}}})
  ;; => true

  ;; Validate Artifact
  (m/validate Artifact
              {:artifact/id (random-uuid)
               :artifact/type :code
               :artifact/version "1.0.0"
               :artifact/content "(defn hello [] \"world\")"})
  ;; => true

  ;; Validate Workflow
  (m/validate Workflow
              {:workflow/id (random-uuid)
               :workflow/name "feature-auth"
               :workflow/status :running
               :workflow/phase :implement
               :workflow/priority 5})
  ;; => true

  ;; Explain invalid data
  (m/explain Agent {:agent/id "not-a-uuid" :agent/role :invalid})

  :leave-this-here)
