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

(ns ai.miniforge.automation-edge-correlator.schema
  "Open Malli schemas for the AutomationEdge wire form.

   Mirrors the Rust contract in
   `miniforge-control/contracts/crates/supervisory-entities/src/entities.rs`
   (`AutomationEdge` struct) and the RFC at
   `docs/design/automation-edge-correlator.md` §Schema.

   All maps are open (additional keys pass through) per the supervisory-state
   convention from N5-delta-1 §12.4. Enums are closed sets expressed as
   `[:enum ...]`.")

;------------------------------------------------------------------------------ Layer 0
;; Closed enums — match Rust RoutingTriggerKind and AutomationEdgeStatus variants

(def routing-trigger-kinds
  "All recognised RoutingTriggerKind values, matching the Rust enum in
   `miniforge-control/contracts/crates/supervisory-entities/src/entities.rs`.
   Vector for deterministic ordering in malli error messages."
  [:pr-merged
   :review-comments-arrived
   :ci-failed
   :standards-review-arrived
   :workflow-completed
   :gate-fired])

(def automation-edge-statuses
  "All recognised AutomationEdge status values, matching the Rust enum.
   Vector for deterministic ordering."
  [:observed
   :handled
   :failed
   :needs-operator
   :suppressed])

;------------------------------------------------------------------------------ Layer 0a
;; Registry — minimal primitives, no cross-boundary reach into schema/core

(def registry
  "Malli registry for automation-edge-correlator keyword types.

   Inlines the base keyword types (`id/uuid`, `:common/timestamp`,
   `:common/non-neg-int`) rather than depending on
   `ai.miniforge.schema.core/registry` — keeps the component from reaching
   across a Polylith boundary, matching supervisory-state convention."
  {;; Primitives
   :id/uuid            uuid?
   :common/timestamp   inst?
   :common/non-neg-int [:int {:min 0}]

   ;; AutomationEdge sub-types
   :edge/id                 :id/uuid
   :edge/trigger-event-id   :id/uuid
   :edge/trigger-kind       (into [:enum] routing-trigger-kinds)
   :edge/status             (into [:enum] automation-edge-statuses)})

;------------------------------------------------------------------------------ Layer 1
;; Entity schema

(def AutomationEdge
  "Wire form for a single automation causality edge emitted as
   `:supervisory/automation-edge-upserted`.

   Mirrors the Rust `AutomationEdge` struct one-to-one. Open map: additional
   keys pass through validation. Each field corresponds to the RFC §Schema
   definition.

   Idempotency key: `:edge/id` is a deterministic UUIDv5 derived from
   `:edge/trigger-event-id` alone — not from the handler workflow id — so
   status transitions upsert the same row. See RFC §Idempotency."
  [:map {:registry registry}
   ;; Required identity fields
   [:edge/id                :edge/id]
   [:edge/trigger-event-id  :edge/trigger-event-id]
   [:edge/trigger-kind      :edge/trigger-kind]
   [:edge/status            :edge/status]
   [:edge/idempotency-key   string?]
   [:edge/occurred-at       :common/timestamp]
   [:edge/updated-at        :common/timestamp]

   ;; Optional — populated once a handler workflow is correlated
   [:edge/spec-id {:optional true}
    [:maybe :id/uuid]]
   [:edge/handled-by-workflow-run-id {:optional true}
    [:maybe :id/uuid]]

   ;; Optional — derived from trigger payload; may be empty vectors when
   ;; trigger doesn't carry sufficient payload (RFC §Affected PRs and agents)
   [:edge/affected-pr-ids {:optional true}
    [:vector [:tuple :string :common/non-neg-int]]]
   [:edge/affected-agent-session-ids {:optional true}
    [:vector :id/uuid]]

   ;; Optional — populated on :needs-operator or :failed transitions
   [:edge/operator-action-required {:optional true} boolean?]
   [:edge/fallback-intervention    {:optional true} [:maybe keyword?]]])
