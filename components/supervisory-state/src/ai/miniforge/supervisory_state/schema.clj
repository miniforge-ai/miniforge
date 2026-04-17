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
   N5-delta-1 §12.4."
  (:require
   [ai.miniforge.schema.interface :as schema-core]))

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

;------------------------------------------------------------------------------ Layer 0a
;; Registry extensions for supervisory v1

(def registry
  "Malli registry extending core registry with supervisory v1 keyword types."
  (merge
   schema-core/registry
   {;; WorkflowRun
    :workflow-run/id              :id/uuid
    :workflow-run/status          (into [:enum] workflow-run-statuses)
    :workflow-run/trigger-source  (into [:enum] trigger-sources)

    ;; AgentSession (overlaps with :agent/id from core; supervisory expects UUID)
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
    :attention/source-type        (into [:enum] attention-source-types)}))

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
   [:workflow-run/correlation-id :id/uuid]])

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

(def PrFleetEntry
  "A pull request observable in the supervisory PR fleet view (N5/N9)."
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
   [:pr/merged-at {:optional true} [:maybe :common/timestamp]]])

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
   [:policy-eval/target-type :policy-eval/target-type]
   [:policy-eval/target-id any?]
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

;------------------------------------------------------------------------------ Layer 2
;; Component-internal entity table

(def EntityTable
  "Aggregate state held by the supervisory-state component."
  [:map
   [:workflows     [:map-of :id/uuid WorkflowRun]]
   [:agents        [:map-of :id/uuid AgentSession]]
   [:prs           [:map-of [:tuple string? :common/non-neg-int] PrFleetEntry]]
   [:policy-evals  [:map-of :id/uuid PolicyEvaluation]]
   [:attention     [:map-of :id/uuid AttentionItem]]])

(def empty-table
  "Initial empty entity table."
  {:workflows {}
   :agents {}
   :prs {}
   :policy-evals {}
   :attention {}})
