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
(ns ai.miniforge.schema.vocab
  "Base enum vocabularies, severity helpers, and the shared malli registry
   for the schema component. Split out of `core.clj` (SL003, Wave 2) so the
   composite entity schemas in `core.clj` have a vocabulary to depend on
   without pushing either file over the 3-layer budget.
   Layer 0: Base enum vocabularies and severity pass-throughs
   Layer 1: registry — the shared malli registry, built from Layer 0
   Layer 2: Severity — the constructed enum schema, built from registry"
  (:require
   [ai.miniforge.policy-clause.interface :as clause]))

;------------------------------------------------------------------------------ Layer 0

;; Base enum vocabularies and severity helpers
(def ^{:stratum 0} agent-roles
  [:planner :architect :implementer :tester :reviewer :sre :security :release :historian :operator])

(def ^{:stratum 0} meta-agent-roles
  "Meta-agent roles for workflow monitoring and control."
  [:progress-monitor :test-quality :conflict-detector :resource-manager :evidence-collector])

(def ^{:stratum 0} task-types
  [:plan :design :implement :test :review :deploy])

(def ^{:stratum 0} task-statuses
  [:pending :running :completed :failed :blocked])

(def ^{:stratum 0} artifact-types
  [:spec :plan :adr :code :test :review :manifest :image :telemetry :incident])

(def ^{:stratum 0} workflow-phases
  [:plan :design :implement :verify :review :release :observe])

(def ^{:stratum 0} workflow-statuses
  [:pending :running :paused :completed :failed :cancelled])

(def ^{:stratum 0} severities
  "Canonical severity levels, most to least severe. Defined by the
   policy-clause component (Ariadne step 1a) and passed through here so
   every schema consumer keeps one import site. A violation's severity
   is the severity of the rule it violates, so one scale — not a
   per-producer vocabulary. Legacy `:major`/`:minor` map to
   `:high`/`:low` via `normalize-severity`."
  clause/severities)

(def ^{:stratum 0} normalize-severity
  "Coerce a legacy severity keyword to the canonical enum: `:major` → `:high`,
   `:minor` → `:low`; every canonical value is returned unchanged. Pass-through
   to policy-clause."
  clause/normalize-severity)

(def ^{:stratum 0} severity-order
  "Rank per severity, 0 = most severe. Pass-through to policy-clause so the order
   table cannot drift from the enum."
  clause/severity-order)

(def ^{:stratum 0} compare-severity
  "Compare two severities. Negative when `a` is more severe than `b`, positive
   when less, 0 when equal. An unknown severity on either side returns an
   `:invalid-input` anomaly (subtype `:anomalies.policy-clause/unknown-severity`)
   — the pre-Ariadne sort-unknown-last default was a fail-open and is gone."
  clause/compare-severity)

(def ^{:stratum 0} more-severe
  "Return the more severe of two severities, or an anomaly when either is
   unknown (see `compare-severity`)."
  clause/more-severe)

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} registry
  "Malli registry for base schema types."
  {;; Identifiers
   :id/uuid        uuid?
   :id/string      [:string {:min 1}]

   ;; Agent types
   :agent/id       :id/uuid
   :agent/role     (into [:enum] agent-roles)
   :agent/capability keyword?

   ;; Meta-agent types
   :meta-agent/id  keyword?
   :meta-agent/role (into [:enum] meta-agent-roles)
   :meta-agent/status [:enum :healthy :warning :halt]
   :meta-agent/priority [:enum :high :medium :low]

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

   ;; Severity (canonical, shared across policy + supervisory + display)
   :severity (into [:enum] severities)

   ;; Common types
   :common/timestamp inst?
   :common/non-neg-int [:int {:min 0}]
   :common/pos-number [:double {:min 0.0}]})

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Severity
  "Malli enum for a canonical severity level (see `severities`). Reuses the
   `:severity` registry entry so there is one constructed enum, not two copies."
  (:severity registry))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Base registry has entries for every domain id/enum
  (contains? registry :agent/role)
  ;; => true

  ;; Severity enum matches the canonical severities vocabulary
  (= severities [:critical :high :medium :low :info])
  ;; => true

  :leave-this-here)
