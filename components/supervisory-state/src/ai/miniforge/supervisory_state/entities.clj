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
(ns ai.miniforge.supervisory-state.entities
  "Open Malli schemas for the ten canonical supervisory entities, plus the
   aggregate EntityTable that holds them, per N5-delta-supervisory-control-
   plane §3.1 and the Rust supervisory-entities crate.

   Built on the enum vocabulary, Malli `registry`, and standalone PR-scoring
   sub-schemas (ReadinessFactor, RiskFactor, PolicyViolationSummary,
   PolicyCounts) defined in `ai.miniforge.supervisory-state.schema` — this
   namespace was split out of `schema.clj` (SL003, Wave 2) because these
   entity schemas compose `registry` and, in turn, PrFleetEntry and
   PolicyEvaluation compose other entities here, which together pushed the
   single-file layer count over the 3-layer stratum budget.

   All maps are open (additional keys pass through) per N5-delta-1 §12.4."
  (:require
   [ai.miniforge.supervisory-state.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

;; Entity schemas — open maps, mirror N5-delta-1 §3.1
(def ^{:stratum 0} WorkflowRun
  "A concrete execution instance of a workflow per N5-delta-1 §3.1.

   Open: additional keys pass through validation."
  [:map {:registry schema/registry}
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
   ;; N14: foreign key to the long-lived `Spec` entity that owns this
   ;; run. Optional — runs without a derivable spec identity (no title
   ;; on the snapshot) remain Specless. Distinct from
   ;; `:workflow-run/spec` (the per-run snapshot above) and stable
   ;; across re-executions of the same spec.
   [:workflow-run/spec-id {:optional true} :spec/id]
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

(def ^{:stratum 0} Spec
  "A long-lived supervisory entity representing the operator's
   top-level unit of work (N5-delta-3 §3.1, §5.1). One Spec owns N
   WorkflowRuns over its lifetime.

   Open map: additional keys pass through validation. Field types
   chosen to be compatible with the existing per-run snapshot
   (`:workflow-run/spec` above) and the spec-parser's `SpecIntent`
   shape:

   - `:spec/intent` is a structured map (per `SpecIntent` in
     `spec-parser/.../schema.clj`); kept as open `map?` here so we
     don't re-validate against the producer-side schema.
   - `:spec/tags` accepts strings OR keywords (existing tag
     conventions elsewhere in the codebase).
   - `:spec/origin` discriminates `:miniforge` (specs known to this
     runtime) from `:local-synthetic` (the Rust-core consumer
     creates these ahead of upstream knowing about them, per
     N5-delta-3 §5.3 reconciliation)."
  [:map {:registry schema/registry}
   [:spec/id         :spec/id]
   [:spec/title      [:string {:min 1}]]
   [:spec/status     :spec/status]
   [:spec/created-at :common/timestamp]
   [:spec/updated-at :common/timestamp]
   [:spec/description {:optional true} :string]
   [:spec/intent      {:optional true} map?]
   [:spec/repo-url    {:optional true} :string]
   [:spec/tags        {:optional true} [:vector [:or :string :keyword]]]
   [:spec/origin      {:optional true} keyword?]])

(def ^{:stratum 0} AgentSession
  "An external or internal agent observable to the supervisory plane.

   Mirrors the Rust supervisory-entities `AgentSession` shape. The
   control-plane/registry component (N5-delta-1 §3.3) is the in-process
   source; supervisory-state mirrors it from `:control-plane/agent-*` events."
  [:map {:registry schema/registry}
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

(def ^{:stratum 0} PrReadiness
  "Merge-readiness score block per N5-delta-2 §2.1."
  [:map {:registry schema/registry}
   [:readiness/score number?]
   [:readiness/threshold number?]
   [:readiness/ready? boolean?]
   [:readiness/factors [:vector schema/ReadinessFactor]]])

(def ^{:stratum 0} PrRisk
  "Risk-assessment score block per N5-delta-2 §2.2."
  [:map {:registry schema/registry}
   [:risk/score number?]
   [:risk/level :risk/level]
   [:risk/factors [:vector schema/RiskFactor]]])

(def ^{:stratum 0} PrPolicy
  "Aggregated external-PR policy result per N5-delta-2 §2.3."
  [:map {:registry schema/registry}
   [:policy/overall :policy/overall]
   [:policy/packs-applied [:vector string?]]
   [:policy/summary schema/PolicyCounts]
   [:policy/violations [:vector schema/PolicyViolationSummary]]
   [:policy/artifacts-checked {:optional true} [:maybe :common/non-neg-int]]])

(def ^{:stratum 0} PolicyViolation
  "A single rule failure within a PolicyEvaluation per N5-delta-1 §3.1."
  [:map {:registry schema/registry}
   [:violation/rule-id keyword?]
   [:violation/severity :violation/severity]
   [:violation/category :violation/category]
   [:violation/message [:string {:min 1}]]
   [:violation/location {:optional true} [:maybe string?]]
   [:violation/remediable? boolean?]])

(def ^{:stratum 0} AttentionItem
  "A derived supervisory signal per N5-delta-1 §3.1 + §5."
  [:map {:registry schema/registry}
   [:attention/id :attention/id]
   [:attention/severity :attention/severity]
   [:attention/source-type :attention/source-type]
   [:attention/source-id any?]
   [:attention/summary [:string {:min 1}]]
   [:attention/derived-at :common/timestamp]
   [:attention/resolved? boolean?]])

(def ^{:stratum 0} TaskNode
  "A single DAG task observable in the Kanban view (N5 §3.2.5 / N5-δ3 §2.3).

   `:task/status` is an OPEN keyword — workflow families may add their own
   statuses via the DAG state-profile system. `:task/kanban-column` is the
   closed display contract derived from status + dependency resolution per
   N5-δ3 §2.3's status→column table; unknown statuses fall back to
   `:blocked` so they surface visibly."
  [:map {:registry schema/registry}
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

(def ^{:stratum 0} DecisionCard
  "A pending or resolved decision request from an agent per N5-δ3 §2.4.

   Today's `:control-plane/decision-created` / `-resolved` events carry a
   thin payload (id, agent-id, summary, optional priority; resolution on
   resolve). Richer fields — `:decision/type`, `:decision/context`,
   `:decision/options`, `:decision/deadline`, `:decision/comment` — are
   part of the entity shape so future control-plane extensions can
   surface them; supervisory-state populates only what arrives on the
   wire, per the open-map rule."
  [:map {:registry schema/registry}
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

(def ^{:stratum 0} InterventionRequest
  "A bounded supervisory control request per N5 supervisory delta §3.1.

   The type and target-type stay open keywords at this boundary so replay and
   downstream consumers preserve future spec-aligned additions."
  [:map {:registry schema/registry}
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

(def ^{:stratum 0} DependencyHealth
  "Projected health for an external provider, platform, or user environment."
  [:map {:registry schema/registry}
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

;------------------------------------------------------------------------------ Layer 1

;; Composite entities — fold in the Layer 0 entities above
(def ^{:stratum 1} PrFleetEntry
  "A pull request observable in the supervisory PR fleet view (N5/N9).

   The four `:pr/readiness`, `:pr/risk`, `:pr/policy`, `:pr/recommendation`
   fields are OPTIONAL pre-computed scores produced by the pr-scoring
   component per N5-delta-2. Absent = \"not yet scored\" (§5.4),
   distinct from a zero score. Consumers MUST NOT recompute."
  [:map {:registry schema/registry}
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

(def ^{:stratum 1} PolicyEvaluation
  "Immutable record of a completed policy evaluation per N5-delta-1 §3.1.

  A re-evaluation MUST produce a new record with a fresh `:policy-eval/id`
   rather than mutate a prior one."
  [:map {:registry schema/registry}
   [:policy-eval/id :policy-eval/id]
   [:policy-eval/workflow-run-id {:optional true} [:maybe :id/uuid]]
   [:policy-eval/gate-id {:optional true} [:maybe keyword?]]
   [:policy-eval/target-type {:optional true} [:maybe :policy-eval/target-type]]
   [:policy-eval/target-id {:optional true} [:maybe any?]]
   [:policy-eval/passed? boolean?]
   [:policy-eval/packs-applied [:vector string?]]
   [:policy-eval/violations [:vector PolicyViolation]]
   [:policy-eval/evaluated-at :common/timestamp]])

;------------------------------------------------------------------------------ Layer 2

;; Component-internal entity table — aggregates every entity above
(def ^{:stratum 2} EntityTable
  "Aggregate state held by the supervisory-state component."
  [:map
   [:specs         [:map-of :id/uuid Spec]]
   [:workflows     [:map-of :id/uuid WorkflowRun]]
   [:agents        [:map-of :id/uuid AgentSession]]
   [:prs           [:map-of [:tuple string? :common/non-neg-int] PrFleetEntry]]
   [:policy-evals  [:map-of :id/uuid PolicyEvaluation]]
   [:attention     [:map-of :id/uuid AttentionItem]]
   [:tasks         [:map-of :id/uuid TaskNode]]
   [:decisions     [:map-of :id/uuid DecisionCard]]
   [:interventions [:map-of :id/uuid InterventionRequest]]
   [:dependencies  [:map-of :dependency/id DependencyHealth]]])
