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
  "Logging schemas for miniforge structured EDN logging. Event/level/category
   vocabularies and the logging registry live in `logging_vocab.clj` (split
   out under SL003, Wave 2) — this file only holds the `:map` composites
   built on top of that registry.
   Layer 0: Composite schemas (LogContext, ScenarioContext, TraceContext,
            PerfMetrics, LogEntry, Scenario) — each depends only on
            `logging-vocab/logging-registry`, not on one another"
  (:require
   [malli.core :as m]
   [ai.miniforge.schema.logging-vocab :as logging-vocab]))

;------------------------------------------------------------------------------ Layer 0

;; Composite schemas
(def ^{:stratum 0} LogContext
  "Schema for log entry context fields."
  [:map {:registry logging-vocab/logging-registry}
   [:ctx/workflow-id {:optional true} :workflow/id]
   [:ctx/task-id {:optional true} :task/id]
   [:ctx/agent-id {:optional true} :agent/id]
   [:ctx/phase {:optional true} :workflow/phase]
   [:ctx/loop {:optional true} :log/loop-type]])

(def ^{:stratum 0} ScenarioContext
  "Schema for scenario tracking in logs."
  [:map {:registry logging-vocab/logging-registry}
   [:scenario/id {:optional true} :id/uuid]
   [:scenario/tags {:optional true} [:set :scenario/tag]]])

(def ^{:stratum 0} TraceContext
  "Schema for distributed tracing correlation."
  [:map {:registry logging-vocab/logging-registry}
   [:trace/id {:optional true} :id/uuid]
   [:span/id {:optional true} :id/uuid]
   [:parent-span/id {:optional true} :id/uuid]])

(def ^{:stratum 0} PerfMetrics
  "Schema for performance metrics in log entries."
  [:map {:registry logging-vocab/logging-registry}
   [:perf/duration-ms {:optional true} :common/non-neg-int]
   [:perf/tokens-used {:optional true} :common/non-neg-int]
   [:perf/cost-usd {:optional true} :common/pos-number]])

(def ^{:stratum 0} LogEntry
  "Schema for a structured log entry.
   Core data substrate for debugging, tracing, and meta loop signals."
  [:map {:registry logging-vocab/logging-registry}
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

(def ^{:stratum 0} Scenario
  "Schema for a test scenario definition."
  [:map {:registry logging-vocab/logging-registry}
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
  (every? #(m/validate (into [:enum] logging-vocab/all-events) %) logging-vocab/all-events)
  ;; => true

  :leave-this-here)
