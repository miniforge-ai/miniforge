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

(ns ai.miniforge.schema.logging
  "Logging schemas for miniforge structured EDN logging.
   Layer 0: Base types and event taxonomy
   Layer 1: Composite schemas (LogEntry, Scenario)"
  (:require
   [malli.core :as m]
   [ai.miniforge.schema.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Base types and event taxonomy

(def log-levels
  "Log severity levels ordered from most to least verbose."
  [:trace :debug :info :warn :error :fatal])

(def log-categories
  "High-level categorization of log events."
  [:agent :loop :policy :artifact :system])

(def loop-types
  "Types of control loops in the system."
  [:inner :outer :meta])

(def agent-events
  "Events emitted by agent operations."
  [:agent/task-started
   :agent/task-completed
   :agent/task-failed
   :agent/prompt-sent
   :agent/response-received
   :agent/memory-updated])

(def loop-events
  "Events emitted by control loops."
  [:inner/iteration-started
   :inner/validation-passed
   :inner/validation-failed
   :inner/repair-attempted
   :inner/escalated
   :outer/phase-entered
   :outer/phase-completed
   :outer/phase-failed
   :outer/workflow-completed
   :meta/signal-collected
   :meta/improvement-proposed
   :meta/improvement-applied])

(def policy-events
  "Events emitted by policy evaluation."
  [:policy/gate-evaluated
   :policy/budget-checked
   :policy/budget-exceeded
   :policy/escalation
   :policy/human-required
   :policy/human-approved])

(def artifact-events
  "Events emitted by artifact operations."
  [:artifact/created
   :artifact/versioned
   :artifact/linked
   :artifact/validation])

(def system-events
  "Events emitted by system operations."
  [:system/startup
   :system/shutdown
   :system/config-changed
   :system/plugin-loaded
   :system/health-check])

(def all-events
  "All known log events."
  (vec (concat agent-events loop-events policy-events artifact-events system-events)))

(def scenario-tags
  "Standard tags for scenario-based testing."
  [:canary :shadow :regression :smoke :stress :chaos :golden-path :error-recovery :rollback])

(def logging-registry
  "Malli registry for logging schema types."
  (merge
   core/registry
   {:log/id        :id/uuid
    :log/level     (into [:enum] log-levels)
    :log/category  (into [:enum] log-categories)
    :log/event     (into [:enum] all-events)
    :log/loop-type (into [:enum] loop-types)
    :scenario/tag  (into [:enum] scenario-tags)}))

;------------------------------------------------------------------------------ Layer 1
;; Composite schemas

(def LogContext
  "Schema for log entry context fields."
  [:map {:registry logging-registry}
   [:ctx/workflow-id {:optional true} :workflow/id]
   [:ctx/task-id {:optional true} :task/id]
   [:ctx/agent-id {:optional true} :agent/id]
   [:ctx/phase {:optional true} :workflow/phase]
   [:ctx/loop {:optional true} :log/loop-type]])

(def ScenarioContext
  "Schema for scenario tracking in logs."
  [:map {:registry logging-registry}
   [:scenario/id {:optional true} :id/uuid]
   [:scenario/tags {:optional true} [:set :scenario/tag]]])

(def TraceContext
  "Schema for distributed tracing correlation."
  [:map {:registry logging-registry}
   [:trace/id {:optional true} :id/uuid]
   [:span/id {:optional true} :id/uuid]
   [:parent-span/id {:optional true} :id/uuid]])

(def PerfMetrics
  "Schema for performance metrics in log entries."
  [:map {:registry logging-registry}
   [:perf/duration-ms {:optional true} :common/non-neg-int]
   [:perf/tokens-used {:optional true} :common/non-neg-int]
   [:perf/cost-usd {:optional true} :common/pos-number]])

(def LogEntry
  "Schema for a structured log entry.
   Core data substrate for debugging, tracing, and meta loop signals."
  [:map {:registry logging-registry}
   ;; Required fields
   [:log/id :log/id]
   [:log/timestamp :common/timestamp]
   [:log/level :log/level]
   [:log/category :log/category]
   [:log/event :log/event]

   ;; Optional message
   [:log/message {:optional true} [:string {:min 1}]]

   ;; Context (merged from LogContext)
   [:ctx/workflow-id {:optional true} :workflow/id]
   [:ctx/task-id {:optional true} :task/id]
   [:ctx/agent-id {:optional true} :agent/id]
   [:ctx/phase {:optional true} :workflow/phase]
   [:ctx/loop {:optional true} :log/loop-type]

   ;; Scenario tracking
   [:scenario/id {:optional true} :id/uuid]
   [:scenario/tags {:optional true} [:set :scenario/tag]]

   ;; Event-specific payload
   [:data {:optional true} [:map-of keyword? any?]]

   ;; Distributed tracing
   [:trace/id {:optional true} :id/uuid]
   [:span/id {:optional true} :id/uuid]
   [:parent-span/id {:optional true} :id/uuid]

   ;; Performance metrics
   [:perf/duration-ms {:optional true} :common/non-neg-int]
   [:perf/tokens-used {:optional true} :common/non-neg-int]
   [:perf/cost-usd {:optional true} :common/pos-number]])

(def Scenario
  "Schema for a test scenario definition."
  [:map {:registry logging-registry}
   [:scenario/id :id/uuid]
   [:scenario/name [:string {:min 1}]]
   [:scenario/tags {:optional true} [:set :scenario/tag]]
   [:scenario/created-at :common/timestamp]
   [:scenario/created-by {:optional true} [:string {:min 1}]]
   [:scenario/config {:optional true} [:map-of keyword? any?]]
   [:scenario/expected {:optional true} [:map-of keyword? any?]]
   [:scenario/status [:enum :running :passed :failed :cancelled]]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate LogEntry
  (m/validate LogEntry
              {:log/id (random-uuid)
               :log/timestamp (java.util.Date.)
               :log/level :info
               :log/category :agent
               :log/event :agent/task-started
               :log/message "Starting implementation task"
               :ctx/workflow-id (random-uuid)
               :ctx/agent-id (random-uuid)
               :ctx/phase :implement
               :ctx/loop :inner
               :data {:agent-role :implementer
                      :task-type :implement}})
  ;; => true

  ;; Validate Scenario
  (m/validate Scenario
              {:scenario/id (random-uuid)
               :scenario/name "happy-path-auth"
               :scenario/tags #{:golden-path :smoke}
               :scenario/created-at (java.util.Date.)
               :scenario/status :running})
  ;; => true

  ;; Explain invalid LogEntry
  (m/explain LogEntry {:log/id "not-uuid" :log/level :invalid})

  ;; Check all events are valid
  (every? #(m/validate (into [:enum] all-events) %) all-events)
  ;; => true

  :leave-this-here)
