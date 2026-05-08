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
  "Open Malli schemas for the canonical supervisory entities.

   Mirrors N5-delta-supervisory-control-plane §3.1 and the Rust
   supervisory-entities crate (`miniforge-control/contracts/crates/
   supervisory-entities/src/entities.rs`). Key namespaces are deliberately
   the more-explicit `:workflow-run/*`, `:policy-eval/*`, `:attention/*`
   forms used in N5-delta-1 — distinct from the older `:workflow/*` keys
   in `ai.miniforge.schema.supervisory` which were never wired into a
   producer.

   All maps are open (additional keys pass through) per
   N5-delta-1 §12.4.")

;------------------------------------------------------------------------------ Layer 0
;; Enums — match the Rust enum variants in supervisory-entities

(def workflow-run-statuses
  [:queued :running :paused :blocked :completed :failed :cancelled])

(def trigger-sources
  [:mcp :cli :api :chain])

(def agent-statuses
  [:idle :starting :executing :blocked :completed :failed :unreachable :unknown :running :terminated])

(def pr-statuses
  [:draft :open :reviewing :changes-requested :approved :merging :merged :closed :failed])

(def ci-statuses
  [:pending :running :passed :failed :skipped])

(def violation-severities
  [:info :low :medium :high :critical])

(def violation-categories
  [:style :security :testing :documentation :architecture :process :budget])

(def attention-severities
  [:critical :warning :info])

(def attention-source-types
  [:workflow :pr :train :policy :agent])

(def policy-target-types
  [:pr :artifact :workflow-output])

(def risk-levels
  "Pre-computed risk tier from pr-train.risk/assess-risk, per N5-delta-2 §2.2."
  [:low :medium :high :critical])

(def policy-overall-states
  "Aggregate policy-evaluation summary per N5-delta-2 §2.3. `:waived` reflects
   that a Waiver record covers every violation; `:unknown` is the initial
   state before policy has run."
  [:pass :fail :waived :unknown])

(def pr-recommendations
  "Single-keyword action suggestion per N5-delta-2 §2.4. Consumers render
   the keyword verbatim."
  [:merge :approve :review :remediate :decompose :wait :escalate])

(def task-kanban-columns
  "Closed six-column set for the DAG Kanban view (N5 §3.2.5 / N5-δ3 §2.3).
   This is a display contract: adding a column breaks consumers."
  [:blocked :ready :active :in-review :merging :done])

(def decision-types
  "Known decision request shapes per N5-δ3 §2.4. Open — producer may emit
   additional types (control-plane can extend without a spec bump)."
  [:approval :choice :input :confirmation :unknown])

(def decision-priorities
  "Known priority tiers per N5-δ3 §2.4. Open by convention."
  [:critical :high :medium :low])

(def decision-statuses
  "Lifecycle status of a DecisionCard per N5-δ3 §2.4. `:expired` is
   reserved for the future `:control-plane/decision-expired` event or
   a deadline-vs-wall-clock derivation; today's producers emit only
   `:pending` (on create) and `:resolved` (on resolve)."
  [:pending :resolved :expired])

(def intervention-states
  "Lifecycle states of an InterventionRequest per
   N5-delta-supervisory-control-plane §3.3."
  [:proposed :pending-human :approved :rejected :dispatched :applied :verified :failed])

(def dependency-kinds
  [:provider :platform :environment])

(def dependency-statuses
  [:healthy :degraded :unavailable :misconfigured :operator-action-required])

;------------------------------------------------------------------------------ Layer 0a
;; Registry extensions for supervisory v1

(def registry
  "Malli registry for supervisory v1 keyword types.

   Inlines the few base keyword types the supervisory entities need
   (`:id/uuid`, `:common/timestamp`, `:common/non-neg-int`) rather than
   depending on `ai.miniforge.schema.core/registry`. That registry is
   intentionally not on the schema component's public interface, and
   duplicating 3 lines here keeps supervisory-state from reaching across
   a Polylith boundary."
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
   :dependency/status            (into [:enum] dependency-statuses)})

;------------------------------------------------------------------------------ Layer 1
;; Entity schemas — open maps, mirror N5-delta-1 §3.1

(def WorkflowRun
  "A concrete execution instance of a workflow per N5-delta-1 §3.1.

   Open: additional keys pass through validation."
  [:map {:registry registry}
   [:workflow-run/id :workflow-run/id]
   [:workflow-run/workflow-key [:string {:min 1}]]
   [:workflow-run/intent string?]
   [:workflow-run/status :workflow-run/status]
   [:workflow-run/current-phase keyword?]
   [:workflow-run/started-at :common/timestamp]
   [:workflow-run/updated-at :common/timestamp]
   [:workflow-run/trigger-source :workflow-run/trigger-source]
   [:workflow-run/correlation-id :id/uuid]
   [:workflow-run/artifact-ids {:optional true} [:vector :id/uuid]]
   [:workflow-run/evidence-bundle-id {:optional true} [:maybe :id/uuid]]
   ;; BD-1: canonical run-owned spec identity. Lifted from `:workflow/spec`
   ;; on the lifecycle event so consumers do not have to recover it from
   ;; correlated agent metadata. Open: producers may add further `:spec/*`
   ;; keys without a contract bump.
   [:workflow-run/spec {:optional true}
    [:map
     [:spec/title       {:optional true} [:string {:min 1}]]
     [:spec/description {:optional true} [:string {:min 1}]]
     [:spec/intent      {:optional true} map?]]]
   [:workflow-run/prs {:optional true}
    [:vector
     [:map
      [:pr/repo [:string {:min 1}]]
      [:pr/number :common/non-neg-int]
      [:pr/url string?]
      [:pr/branch string?]
      [:pr/title {:optional true} [:maybe string?]]
      [:pr/author {:optional true} [:maybe string?]]
      [:pr/merge-order {:optional true} [:maybe :common/non-neg-int]]]]]])

(def AgentSession
  "An external or internal agent observable to the supervisory plane.

   Mirrors the Rust supervisory-entities `AgentSession` shape. The
   control-plane/registry component (N5-delta-1 §3.3) is the in-process
   source; supervisory-state mirrors it from `:control-plane/agent-*` events."
  [:map {:registry registry}
   [:agent/id :id/uuid]
   [:agent/vendor [:string {:min 1}]]
   [:agent/external-id [:string {:min 1}]]
   [:agent/name [:string {:min 1}]]
   [:agent/status :agent/status]
   [:agent/capabilities [:vector keyword?]]
   [:agent/heartbeat-interval-ms :common/non-neg-int]
   [:agent/metadata [:map-of any? any?]]
   [:agent/tags [:vector string?]]
   [:agent/registered-at :common/timestamp]
   [:agent/last-heartbeat :common/timestamp]
   [:agent/task {:optional true} [:maybe string?]]])

;; Per-PR scoring sub-entities (N5-delta-2 §2). All three are OPTIONAL on
;; PrFleetEntry: absent = "not yet scored", per §5.4 — distinct from a
;; score of zero.

(def ReadinessFactor
  "One factor breakdown row from pr-train.readiness/explain-readiness
   per N5-delta-2 §2.1."
  [:map
   [:factor keyword?]
   [:weight number?]
   [:score number?]
   [:contribution number?]
   [:explanation {:optional true} [:maybe string?]]])

(def PrReadiness
  "Merge-readiness score block per N5-delta-2 §2.1."
  [:map {:registry registry}
   [:readiness/score number?]
   [:readiness/threshold number?]
   [:readiness/ready? boolean?]
   [:readiness/factors [:vector ReadinessFactor]]])

(def RiskFactor
  "One factor breakdown row from pr-train.risk/assess-risk per N5-delta-2 §2.2."
  [:map
   [:factor keyword?]
   [:weight number?]
   [:value {:optional true} any?]
   [:score number?]
   [:explanation {:optional true} [:maybe string?]]])

(def PrRisk
  "Risk-assessment score block per N5-delta-2 §2.2."
  [:map {:registry registry}
   [:risk/score number?]
   [:risk/level :risk/level]
   [:risk/factors [:vector RiskFactor]]])

(def PolicyViolationSummary
  "Per-violation display row on PrPolicy per N5-delta-2 §2.3. Distinct
   from the authoritative PolicyViolation entity (§3.1 N5-delta-1) —
   this is a display-facing projection."
  [:map
   [:rule-id keyword?]
   [:severity :violation/severity]
   [:message string?]
   [:path {:optional true} string?]
   [:waived? {:optional true} boolean?]])

(def PolicyCounts
  "Violation-severity histogram per N5-delta-2 §2.3."
  [:map
   [:critical :common/non-neg-int]
   [:major    :common/non-neg-int]
   [:minor    :common/non-neg-int]
   [:info     :common/non-neg-int]
   [:total    :common/non-neg-int]])

(def PrPolicy
  "Aggregated external-PR policy result per N5-delta-2 §2.3."
  [:map {:registry registry}
   [:policy/overall :policy/overall]
   [:policy/packs-applied [:vector string?]]
   [:policy/summary PolicyCounts]
   [:policy/violations [:vector PolicyViolationSummary]]
   [:policy/artifacts-checked {:optional true} [:maybe :common/non-neg-int]]])

(def PrFleetEntry
  "A pull request observable in the supervisory PR fleet view (N5/N9).

   The four `:pr/readiness`, `:pr/risk`, `:pr/policy`, `:pr/recommendation`
   fields are OPTIONAL pre-computed scores produced by the pr-scoring
   component per N5-delta-2. Absent = \"not yet scored\" (§5.4),
   distinct from a zero score. Consumers MUST NOT recompute."
  [:map {:registry registry}
   [:pr/repo [:string {:min 1}]]
   [:pr/number :common/non-neg-int]
   [:pr/url string?]
   [:pr/branch string?]
   [:pr/title string?]
   [:pr/status :pr/status]
   [:pr/merge-order :common/non-neg-int]
   [:pr/depends-on [:vector :common/non-neg-int]]
   [:pr/blocks [:vector :common/non-neg-int]]
   [:pr/ci-status :pr/ci-status]
   [:pr/author {:optional true} [:maybe string?]]
   [:pr/additions {:optional true} [:maybe :common/non-neg-int]]
   [:pr/deletions {:optional true} [:maybe :common/non-neg-int]]
   [:pr/changed-files-count {:optional true} [:maybe :common/non-neg-int]]
   [:pr/behind-main {:optional true} [:maybe boolean?]]
   [:pr/merged-at {:optional true} [:maybe :common/timestamp]]
   [:pr/workflow-run-id {:optional true} [:maybe :id/uuid]]
   [:pr/readiness {:optional true} [:maybe PrReadiness]]
   [:pr/risk {:optional true} [:maybe PrRisk]]
   [:pr/policy {:optional true} [:maybe PrPolicy]]
   [:pr/recommendation {:optional true} [:maybe :pr/recommendation]]])

(def PolicyViolation
  "A single rule failure within a PolicyEvaluation per N5-delta-1 §3.1."
  [:map {:registry registry}
   [:violation/rule-id keyword?]
   [:violation/severity :violation/severity]
   [:violation/category :violation/category]
   [:violation/message [:string {:min 1}]]
   [:violation/location {:optional true} [:maybe string?]]
   [:violation/remediable? boolean?]])

(def PolicyEvaluation
  "Immutable record of a completed policy evaluation per N5-delta-1 §3.1.

  A re-evaluation MUST produce a new record with a fresh `:policy-eval/id`
   rather than mutate a prior one."
  [:map {:registry registry}
   [:policy-eval/id :policy-eval/id]
   [:policy-eval/workflow-run-id {:optional true} [:maybe :id/uuid]]
   [:policy-eval/gate-id {:optional true} [:maybe keyword?]]
   [:policy-eval/target-type {:optional true} [:maybe :policy-eval/target-type]]
   [:policy-eval/target-id {:optional true} [:maybe any?]]
   [:policy-eval/passed? boolean?]
   [:policy-eval/packs-applied [:vector string?]]
   [:policy-eval/violations [:vector PolicyViolation]]
   [:policy-eval/evaluated-at :common/timestamp]])

(def AttentionItem
  "A derived supervisory signal per N5-delta-1 §3.1 + §5."
  [:map {:registry registry}
   [:attention/id :attention/id]
   [:attention/severity :attention/severity]
   [:attention/source-type :attention/source-type]
   [:attention/source-id any?]
   [:attention/summary [:string {:min 1}]]
   [:attention/derived-at :common/timestamp]
   [:attention/resolved? boolean?]])

(def TaskNode
  "A single DAG task observable in the Kanban view (N5 §3.2.5 / N5-δ3 §2.3).

   `:task/status` is an OPEN keyword — workflow families may add their own
   statuses via the DAG state-profile system. `:task/kanban-column` is the
   closed display contract derived from status + dependency resolution per
   N5-δ3 §2.3's status→column table; unknown statuses fall back to
   `:blocked` so they surface visibly."
  [:map {:registry registry}
   [:task/id :task/id]
   [:task/workflow-run-id :id/uuid]
   [:task/description string?]
   [:task/type {:optional true} [:maybe keyword?]]
   [:task/component {:optional true} [:maybe string?]]
   [:task/status keyword?]
   [:task/kanban-column :task/kanban-column]
   [:task/dependencies {:optional true} [:vector :id/uuid]]
   [:task/dependents   {:optional true} [:vector :id/uuid]]
   [:task/started-at   {:optional true} [:maybe :common/timestamp]]
   [:task/completed-at {:optional true} [:maybe :common/timestamp]]
   [:task/elapsed-ms   {:optional true} [:maybe :common/non-neg-int]]
   [:task/exclusive-files? {:optional true} boolean?]
   [:task/stratum?         {:optional true} boolean?]])

(def DecisionCard
  "A pending or resolved decision request from an agent per N5-δ3 §2.4.

   Today's `:control-plane/decision-created` / `-resolved` events carry a
   thin payload (id, agent-id, summary, optional priority; resolution on
   resolve). Richer fields — `:decision/type`, `:decision/context`,
   `:decision/options`, `:decision/deadline`, `:decision/comment` — are
   part of the entity shape so future control-plane extensions can
   surface them; supervisory-state populates only what arrives on the
   wire, per the open-map rule."
  [:map {:registry registry}
   [:decision/id :decision/id]
   [:decision/agent-id :id/uuid]
   [:decision/workflow-run-id {:optional true} [:maybe :id/uuid]]
   [:decision/type {:optional true} [:maybe :decision/type]]
   [:decision/priority {:optional true} [:maybe :decision/priority]]
   [:decision/status :decision/status]
   [:decision/summary string?]
   [:decision/context  {:optional true} [:maybe string?]]
   [:decision/options  {:optional true} [:vector string?]]
   [:decision/deadline {:optional true} [:maybe :common/timestamp]]
   [:decision/created-at :common/timestamp]
   [:decision/resolution {:optional true} [:maybe string?]]
   [:decision/comment    {:optional true} [:maybe string?]]
   [:decision/resolved-at {:optional true} [:maybe :common/timestamp]]])

(def InterventionRequest
  "A bounded supervisory control request per N5 supervisory delta §3.1.

   The type and target-type stay open keywords at this boundary so replay and
   downstream consumers preserve future spec-aligned additions."
  [:map {:registry registry}
   [:intervention/id :intervention/id]
   [:intervention/type keyword?]
   [:intervention/target-type keyword?]
   [:intervention/target-id any?]
   [:intervention/requested-by [:string {:min 1}]]
   [:intervention/request-source keyword?]
   [:intervention/state :intervention/state]
   [:intervention/requested-at :common/timestamp]
   [:intervention/updated-at :common/timestamp]
   [:intervention/justification {:optional true} [:maybe string?]]
   [:intervention/details {:optional true} [:maybe map?]]
   [:intervention/approval-required? {:optional true} boolean?]
   [:intervention/reason {:optional true} [:maybe string?]]
   [:intervention/outcome {:optional true} any?]])

(def DependencyHealth
  "Projected health for an external provider, platform, or user environment."
  [:map {:registry registry}
   [:dependency/id :dependency/id]
   [:dependency/source keyword?]
   [:dependency/kind :dependency/kind]
   [:dependency/status :dependency/status]
   [:dependency/failure-count :common/non-neg-int]
   [:dependency/window-size :common/non-neg-int]
   [:dependency/incident-counts [:map-of :dependency/status :common/non-neg-int]]
   [:dependency/vendor {:optional true} [:maybe keyword?]]
   [:dependency/class {:optional true} [:maybe keyword?]]
   [:dependency/retryability {:optional true} [:maybe keyword?]]
   [:failure/class {:optional true} [:maybe keyword?]]
   [:dependency/last-observed-at {:optional true} [:maybe :common/timestamp]]
   [:dependency/last-recovered-at {:optional true} [:maybe :common/timestamp]]])

;------------------------------------------------------------------------------ Layer 2
;; Component-internal entity table

(def EntityTable
  "Aggregate state held by the supervisory-state component."
  [:map
   [:workflows     [:map-of :id/uuid WorkflowRun]]
   [:agents        [:map-of :id/uuid AgentSession]]
   [:prs           [:map-of [:tuple string? :common/non-neg-int] PrFleetEntry]]
   [:policy-evals  [:map-of :id/uuid PolicyEvaluation]]
   [:attention     [:map-of :id/uuid AttentionItem]]
   [:tasks         [:map-of :id/uuid TaskNode]]
   [:decisions     [:map-of :id/uuid DecisionCard]]
   [:interventions [:map-of :id/uuid InterventionRequest]]
   [:dependencies  [:map-of :dependency/id DependencyHealth]]])

(def empty-table
  "Initial empty entity table."
  {:workflows {}
   :agents {}
   :prs {}
   :policy-evals {}
   :attention {}
   :tasks {}
   :decisions {}
   :interventions {}
   :dependencies {}})
