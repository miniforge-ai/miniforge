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
(ns ai.miniforge.supervisory-state.schema
  "Open Malli vocabulary for the canonical supervisory entities: contract
   version, enums, the Malli registry, the standalone PR-scoring sub-schemas,
   and the empty EntityTable seed.

   Mirrors N5-delta-supervisory-control-plane §3.1 and the Rust
   supervisory-entities crate (`miniforge-control/contracts/crates/
   supervisory-entities/src/entities.rs`). Key namespaces are deliberately
   the more-explicit `:workflow-run/*`, `:policy-eval/*`, `:attention/*`
   forms used in N5-delta-1 — distinct from the older `:workflow/*` keys
   in `ai.miniforge.schema.supervisory` which were never wired into a
   producer.

   All maps are open (additional keys pass through) per N5-delta-1 §12.4.

   The twelve canonical entity schemas (WorkflowRun, Spec, AgentSession,
   PrReadiness, PrRisk, PrPolicy, PolicyViolation, AttentionItem, TaskNode,
   DecisionCard, InterventionRequest, DependencyHealth), the composite
   PrFleetEntry/PolicyEvaluation, and the aggregate EntityTable live in
   `ai.miniforge.supervisory-state.entities` — they compose the `registry`
   defined here, which pushed this namespace over the 3-layer stratum
   budget when they lived in one file (SL003, Wave 2)."
  (:require
   [ai.miniforge.schema.interface :as shared]))

;------------------------------------------------------------------------------ Layer 0

;; Contract version
(def ^{:stratum 0} schema-version
  "Version of the supervisory entity contract, stamped as
   `:supervisory/schema-version` on every supervisory snapshot event
   (N3 §3.19): the `:supervisory/*-upserted` families plus
   `:supervisory/policy-evaluated` and `:supervisory/attention-derived`.

   Bump on any change to an entity shape that an external consumer
   could observe: a renamed key, a removed key, a changed enum value,
   or a changed value type. Adding a NEW optional key does NOT require
   a bump — entities are open maps and consumers preserve unknowns.

   Consumers (miniforge-control supervisory-entities crate) pin the
   version their vendored golden fixtures were generated from and warn
   at runtime on mismatch instead of silently dropping fields.

   v2 (Ariadne 1e): the golden corpus now exercises the four
   policy-attention keys the producer always emitted but the corpus
   never showed (:attention/workflow-run-id :attention/gate-id
   :attention/target-type :attention/target-id) and adds the
   :gate/decision event family carrying the DecisionEnvelope — the one
   truth artifact for gated transitions."
  2)

;; Enums — match the Rust enum variants in supervisory-entities
(def ^{:stratum 0} workflow-run-statuses
  [:queued :running :paused :blocked :completed :failed :cancelled])

(def ^{:stratum 0} trigger-sources
  [:mcp :cli :api :chain])

(def ^{:stratum 0} agent-statuses
  [:idle :starting :executing :blocked :completed :failed :unreachable :unknown :running :terminated])

(def ^{:stratum 0} pr-statuses
  [:draft :open :reviewing :changes-requested :approved :merging :merged :closed :failed])

(def ^{:stratum 0} ci-statuses
  [:pending :running :passed :failed :skipped])

(def ^{:stratum 0} violation-severities
  "Pass-through to the canonical severity scale (policy-clause via the
   shared schema interface); enum use only."
  shared/severities)

(def ^{:stratum 0} violation-categories
  [:style :security :testing :documentation :architecture :process :budget])

(def ^{:stratum 0} attention-severities
  [:critical :warning :info])

(def ^{:stratum 0} attention-source-types
  [:workflow :pr :train :policy :agent])

(def ^{:stratum 0} policy-target-types
  [:pr :artifact :workflow-output])

(def ^{:stratum 0} risk-levels
  "Pre-computed risk tier from pr-train.risk/assess-risk, per N5-delta-2 §2.2."
  [:low :medium :high :critical])

(def ^{:stratum 0} policy-overall-states
  "Aggregate policy-evaluation summary per N5-delta-2 §2.3. `:waived` reflects
   that a Waiver record covers every violation; `:unknown` is the initial
   state before policy has run."
  [:pass :fail :waived :unknown])

(def ^{:stratum 0} pr-recommendations
  "Single-keyword action suggestion per N5-delta-2 §2.4. Consumers render
   the keyword verbatim."
  [:merge :approve :review :remediate :decompose :wait :escalate])

(def ^{:stratum 0} task-kanban-columns
  "Closed six-column set for the DAG Kanban view (N5 §3.2.5 / N5-δ3 §2.3).
   This is a display contract: adding a column breaks consumers."
  [:blocked :ready :active :in-review :merging :done])

(def ^{:stratum 0} decision-types
  "Known decision request shapes per N5-δ3 §2.4. Open — producer may emit
   additional types (control-plane can extend without a spec bump)."
  [:approval :choice :input :confirmation :unknown])

(def ^{:stratum 0} decision-priorities
  "Known priority tiers per N5-δ3 §2.4. Open by convention."
  [:critical :high :medium :low])

(def ^{:stratum 0} decision-statuses
  "Lifecycle status of a DecisionCard per N5-δ3 §2.4. `:expired` is
   reserved for the future `:control-plane/decision-expired` event or
   a deadline-vs-wall-clock derivation; today's producers emit only
   `:pending` (on create) and `:resolved` (on resolve)."
  [:pending :resolved :expired])

(def ^{:stratum 0} intervention-states
  "Lifecycle states of an InterventionRequest per
   N5-delta-supervisory-control-plane §3.3."
  [:proposed :pending-human :approved :rejected :dispatched :applied :verified :failed])

(def ^{:stratum 0} dependency-kinds
  [:provider :platform :environment])

(def ^{:stratum 0} dependency-statuses
  [:healthy :degraded :unavailable :misconfigured :operator-action-required])

(def ^{:stratum 0} spec-statuses
  "Lifecycle states a long-lived Spec passes through (N5-delta-3 §5.1).

   - :draft     — created, no MiniforgeRun yet
   - :active    — at least one WorkflowRun in flight (or recently completed)
   - :completed — operator-marked done; no future runs expected
   - :archived  — operator-archived; suppressed from default UI surfaces

   Vector (not set) for deterministic enum ordering in malli printed
   schemas / error messages, matching `workflow-run-statuses` /
   `pr-statuses` / etc. throughout this namespace."
  [:draft :active :completed :archived])

;; Per-PR scoring sub-entities (N5-delta-2 §2). All three are OPTIONAL on
;; PrFleetEntry (`ai.miniforge.supervisory-state.entities`): absent = "not
;; yet scored", per §5.4 — distinct from a score of zero.
(def ^{:stratum 0} ReadinessFactor
  "One factor breakdown row from pr-train.readiness/explain-readiness
   per N5-delta-2 §2.1."
  [:map
   [:factor keyword?]
   [:weight number?]
   [:score number?]
   [:contribution number?]
   [:explanation {:optional true} [:maybe string?]]])

(def ^{:stratum 0} RiskFactor
  "One factor breakdown row from pr-train.risk/assess-risk per N5-delta-2 §2.2."
  [:map
   [:factor keyword?]
   [:weight number?]
   [:value {:optional true} any?]
   [:score number?]
   [:explanation {:optional true} [:maybe string?]]])

(def ^{:stratum 0} PolicyViolationSummary
  "Per-violation display row on PrPolicy per N5-delta-2 §2.3. Distinct
   from the authoritative PolicyViolation entity (§3.1 N5-delta-1,
   `ai.miniforge.supervisory-state.entities`) — this is a display-facing
   projection."
  [:map
   [:rule-id keyword?]
   [:severity :violation/severity]
   [:message string?]
   [:path {:optional true} string?]
   [:waived? {:optional true} boolean?]])

(def ^{:stratum 0} PolicyCounts
  "Violation-severity histogram per N5-delta-2 §2.3, keyed by the canonical
   5-level severity scale (`:critical/:high/:medium/:low/:info`) so it aligns
   with `:violation/severity`, plus `:total`. The per-severity counts sum to
   `:total`."
  [:map
   [:critical :common/non-neg-int]
   [:high     :common/non-neg-int]
   [:medium   :common/non-neg-int]
   [:low      :common/non-neg-int]
   [:info     :common/non-neg-int]
   [:total    :common/non-neg-int]])

(def ^{:stratum 0} empty-table
  "Initial empty entity table."
  {:specs {}
   :workflows {}
   :agents {}
   :prs {}
   :policy-evals {}
   :attention {}
   :tasks {}
   :decisions {}
   :interventions {}
   :dependencies {}})

;------------------------------------------------------------------------------ Layer 1

;; Registry extensions for supervisory v1
(def ^{:stratum 1} registry
  "Malli registry for supervisory v1 keyword types.

   Inlines the few base keyword types the supervisory entities need
   (`:id/uuid`, `:common/timestamp`, `:common/non-neg-int`) rather than
   depending on `ai.miniforge.schema.core/registry`. That registry is
   intentionally not on the schema component's public interface, and
   duplicating 3 lines here keeps supervisory-state from reaching across
   a Polylith boundary.

   Composes the same-file Layer 0 enums (`workflow-run-statuses`,
   `violation-severities`, `risk-levels`, etc.) into their `:workflow-run/
   status`-style keyword entries below — that's what makes this a genuine
   second layer. Consumed cross-namespace by the entity schemas in
   `ai.miniforge.supervisory-state.entities` via `{:registry schema/registry}`."
  {;; Primitives
   :id/uuid            uuid?
   :common/timestamp   inst?
   :common/non-neg-int [:int {:min 0}]

   ;; WorkflowRun
   :workflow-run/id              :id/uuid
   :workflow-run/status          (into [:enum] workflow-run-statuses)
   :workflow-run/trigger-source  (into [:enum] trigger-sources)

   ;; AgentSession
   :agent/status                 (into [:enum] agent-statuses)

   ;; PrFleetEntry
   :pr/status                    (into [:enum] pr-statuses)
   :pr/ci-status                 (into [:enum] ci-statuses)

   ;; PolicyEvaluation
   :policy-eval/id               :id/uuid
   :policy-eval/target-type      (into [:enum] policy-target-types)

   ;; PolicyViolation
   :violation/severity           (into [:enum] violation-severities)
   :violation/category           (into [:enum] violation-categories)

   ;; AttentionItem
   :attention/id                 :id/uuid
   :attention/severity           (into [:enum] attention-severities)
   :attention/source-type        (into [:enum] attention-source-types)

   ;; PR scoring (N5-delta-2 §2)
   :risk/level                   (into [:enum] risk-levels)
   :policy/overall               (into [:enum] policy-overall-states)
   :pr/recommendation            (into [:enum] pr-recommendations)

   ;; TaskNode (N5-delta-3 §2.3). `:task/status` is intentionally OPEN
   ;; — the DAG state-profile system lets workflow families add statuses
   ;; without a spec bump. `:task/kanban-column` is closed: it is the
   ;; display contract for §3.2.5.
   :task/id                      :id/uuid
   :task/kanban-column           (into [:enum] task-kanban-columns)

   ;; DecisionCard (N5-delta-3 §2.4). Open maps on the entity — these
   ;; registry entries document known values for display, not closed
   ;; enums the producer must emit. Consumers MUST preserve unknowns.
   :decision/id                  :id/uuid
   :decision/type                (into [:enum] decision-types)
   :decision/priority            (into [:enum] decision-priorities)
   :decision/status              (into [:enum] decision-statuses)

   ;; InterventionRequest (N5 supervisory delta §3.1 / §3.3)
   :intervention/id              :id/uuid
   :intervention/state           (into [:enum] intervention-states)

   ;; DependencyHealth
   :dependency/id                keyword?
   :dependency/kind              (into [:enum] dependency-kinds)
   :dependency/status            (into [:enum] dependency-statuses)

   ;; Spec (N5-delta-3 §3.1, §5.1). Long-lived supervisory entity that
   ;; owns N WorkflowRuns over its lifetime. Distinct from the per-run
   ;; `:workflow-run/spec` snapshot (BD-1, miniforge#793) which captures
   ;; spec identity at run-start time only.
   :spec/id                      :id/uuid
   :spec/status                  (into [:enum] spec-statuses)})
