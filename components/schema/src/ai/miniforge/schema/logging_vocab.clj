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
(ns ai.miniforge.schema.logging-vocab
  "Event/level/category vocabularies and the logging malli registry. Split
   out of `logging.clj` (SL003, Wave 2) so the log/scenario composite
   schemas in `logging.clj` have a vocabulary to depend on without pushing
   either file over the 3-layer budget.
   Layer 0: Base event/level/category vocabularies
   Layer 1: all-events (aggregates the Layer 0 event vocabularies)
   Layer 2: logging-registry (the malli registry, extends `vocab/registry`)"
  (:require
   [ai.miniforge.schema.vocab :as vocab]))

;------------------------------------------------------------------------------ Layer 0

;; Base types and event taxonomy
(def ^{:stratum 0} log-levels
  [:trace :debug :info :warn :error :fatal])

(def ^{:stratum 0} log-categories
  [:agent :loop :policy :artifact :system])

(def ^{:stratum 0} loop-types
  "Types of control loops in the system."
  [:inner :outer :meta])

(def ^{:stratum 0} agent-events
  "Events emitted by agent operations."
  [:agent/task-started
   :agent/task-completed
   :agent/task-failed
   :agent/prompt-sent
   :agent/response-received
   :agent/memory-updated])

(def ^{:stratum 0} loop-events
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

(def ^{:stratum 0} policy-events
  "Events emitted by policy evaluation."
  [:policy/gate-evaluated
   :policy/budget-checked
   :policy/budget-exceeded
   :policy/escalation
   :policy/human-required
   :policy/human-approved])

(def ^{:stratum 0} artifact-events
  "Events emitted by artifact operations."
  [:artifact/created
   :artifact/versioned
   :artifact/linked
   :artifact/validation])

(def ^{:stratum 0} system-events
  "Events emitted by system operations."
  [:system/startup
   :system/shutdown
   :system/config-changed
   :system/plugin-loaded
   :system/health-check])

(def ^{:stratum 0} scenario-tags
  [:canary :shadow :regression :smoke :stress :chaos :golden-path :error-recovery :rollback])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} all-events
  (vec (concat agent-events loop-events policy-events artifact-events system-events)))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} logging-registry
  "Malli registry for logging schema types."
  (merge
   vocab/registry
   {:log/id        :id/uuid
    :log/level     (into [:enum] log-levels)
    :log/category  (into [:enum] log-categories)
    :log/event     (into [:enum] all-events)
    :log/loop-type (into [:enum] loop-types)
    :scenario/tag  (into [:enum] scenario-tags)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Check all events are valid
  (require '[malli.core :as m])
  (every? #(m/validate (into [:enum] all-events) %) all-events)
  ;; => true

  :leave-this-here)
