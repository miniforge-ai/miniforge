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
  "Core domain composite schemas for miniforge. Enum vocabularies, severity
   helpers, and the shared malli registry live in `vocab.clj` (split out
   under SL003, Wave 2) — this file only holds the `:map` composites built
   on top of that registry.
   Layer 0: Standalone composites depending only on `vocab/registry`
            (Agent, TaskConstraints, TaskResult, ArtifactOrigin,
            WorkflowBudget, Metrics, MetaAgentConfig, MetaAgentHealthCheck)
   Layer 1: Top-level composites depending on Layer 0
            (Task, Artifact, Workflow, MetaCoordinatorState)"
  (:require
   [ai.miniforge.schema.vocab :as vocab]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

;; Composite schemas
(def ^{:stratum 0} Agent
  "Schema for an AI agent.
   Agents are pure functions: (context, task) -> (artifacts, decisions, signals)"
  [:map {:registry vocab/registry}
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

(def ^{:stratum 0} TaskConstraints
  "Schema for task execution constraints."
  [:map {:registry vocab/registry}
   [:budget {:optional true}
    [:map
     [:tokens {:optional true} :common/non-neg-int]
     [:cost-usd {:optional true} :common/pos-number]
     [:duration-ms {:optional true} :common/non-neg-int]]]
   [:deadline {:optional true} :common/timestamp]
   [:policies {:optional true} [:vector keyword?]]
   [:max-iterations {:optional true} :common/non-neg-int]])

(def ^{:stratum 0} TaskResult
  "Schema for task execution result."
  [:map {:registry vocab/registry}
   [:outcome [:enum :success :failure :escalated]]
   [:error {:optional true} :id/string]
   [:signals {:optional true} [:vector keyword?]]
   [:metrics {:optional true}
    [:map
     [:duration-ms {:optional true} :common/non-neg-int]
     [:tokens-used {:optional true} :common/non-neg-int]
     [:cost-usd {:optional true} :common/pos-number]
     [:iterations {:optional true} :common/non-neg-int]]]])

(def ^{:stratum 0} ArtifactOrigin
  "Schema for artifact provenance origin."
  [:map {:registry vocab/registry}
   [:intent-id {:optional true} :id/uuid]
   [:agent-id {:optional true} :agent/id]
   [:task-id {:optional true} :task/id]])

(def ^{:stratum 0} WorkflowBudget
  "Schema for workflow budget allocation."
  [:map {:registry vocab/registry}
   [:tokens {:optional true} :common/non-neg-int]
   [:cost-usd {:optional true} :common/pos-number]
   [:duration-ms {:optional true} :common/non-neg-int]])

(def ^{:stratum 0} Metrics
  "Schema for an agent/phase result's `:metrics` map. `:tokens` and
   `:duration-ms` are REQUIRED and non-nil — they are consumed by cost and
   accumulation arithmetic, so a missing or nil value is a boundary violation
   (it throws a message-less NPE downstream). `:non-neg-int` already rejects a
   present nil; making the keys required also rejects an absent one."
  [:map {:registry vocab/registry}
   [:tokens :common/non-neg-int]
   [:duration-ms :common/non-neg-int]
   [:cost-usd {:optional true} :common/pos-number]
   [:iterations {:optional true} :common/non-neg-int]])

(def ^{:stratum 0} MetaAgentConfig
  "Schema for meta-agent configuration."
  [:map {:registry vocab/registry}
   [:id :meta-agent/id]
   [:name :id/string]
   [:can-halt? boolean?]
   [:check-interval-ms :common/non-neg-int]
   [:priority :meta-agent/priority]
   [:enabled? boolean?]])

(def ^{:stratum 0} MetaAgentHealthCheck
  "Schema for meta-agent health check result."
  [:map {:registry vocab/registry}
   [:status :meta-agent/status]
   [:agent/id :meta-agent/id]
   [:message :id/string]
   [:data {:optional true} [:map-of keyword? any?]]
   [:checked-at :common/timestamp]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Task
  "Schema for a unit of work assigned to an agent."
  [:map {:registry vocab/registry}
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

(def ^{:stratum 1} Artifact
  "Schema for a versioned work product with provenance."
  [:map {:registry vocab/registry}
   [:artifact/id :artifact/id]
   [:artifact/type :artifact/type]
   [:artifact/version :artifact/version]
   [:artifact/content {:optional true} any?]
   [:artifact/origin {:optional true} ArtifactOrigin]
   [:artifact/parents {:optional true} [:vector :artifact/id]]
   [:artifact/children {:optional true} [:vector :artifact/id]]
   [:artifact/metadata {:optional true} [:map-of keyword? any?]]
   [:artifact/created-at {:optional true} :common/timestamp]])

(def ^{:stratum 1} Workflow
  "Schema for an outer loop SDLC delivery instance."
  [:map {:registry vocab/registry}
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
   [:workflow/meta-agents {:optional true}
    [:vector
     [:map
      [:id :meta-agent/id]
      [:enabled? {:optional true} boolean?]
      [:config {:optional true} [:map-of keyword? any?]]]]]])

(def ^{:stratum 1} MetaCoordinatorState
  "Schema for meta-agent coordinator state."
  [:map {:registry vocab/registry}
   [:status :meta-agent/status]
   [:checks [:vector MetaAgentHealthCheck]]
   [:halt-reason {:optional true} :id/string]
   [:halting-agent {:optional true} :meta-agent/id]
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
