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

(ns ai.miniforge.automation-edge-correlator.interface
  "Public API for the automation-edge-correlator component.

   Exposes schemas, trigger classification (N15-1), and the lifecycle
   surface (N15-3). The pure state-machine transitions in `correlator.clj`
   are deliberately NOT re-exported — they are implementation details
   behind `start!` / `stop!` / `attach!`, and surfacing them would let
   downstream callers reach past the boundary into the fold internals."
  (:require
   [ai.miniforge.automation-edge-correlator.core :as core]
   [ai.miniforge.automation-edge-correlator.schema :as schema]
   [ai.miniforge.automation-edge-correlator.triggers :as triggers]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports
;;
;; `schema/registry` is intentionally NOT re-exported: it is an internal
;; implementation detail that inlines primitive keyword types to avoid a
;; cross-boundary reach into `ai.miniforge.schema.core/registry`. Exposing it
;; would invite downstream callers to depend on its exact key set, turning
;; later consolidation into a breaking change.

(def AutomationEdge
  "Malli schema (vector `[:map ...]` form) for the AutomationEdge wire shape
   emitted as `:supervisory/automation-edge-upserted`. Open map: additional
   keys pass validation. Mirrors the Rust `AutomationEdge` struct one-to-one;
   `:edge/id` is a deterministic UUIDv5 idempotency key derived from
   `:edge/trigger-event-id`. Use with `malli.core` to validate or explain
   edge envelopes at the boundary."
  schema/AutomationEdge)

(def routing-trigger-kinds
  "Vector of keywords — every recognised RoutingTriggerKind value
   (`:pr-merged`, `:review-comments-arrived`, `:ci-failed`,
   `:standards-review-arrived`, `:workflow-completed`, `:gate-fired`).
   Ordered for deterministic malli error messages; matches the Rust enum."
  schema/routing-trigger-kinds)

(def automation-edge-statuses
  "Vector of keywords — every recognised AutomationEdge status value
   (`:observed`, `:handled`, `:failed`, `:needs-operator`, `:suppressed`).
   Ordered for determinism; matches the Rust enum."
  schema/automation-edge-statuses)

;------------------------------------------------------------------------------ Layer 0
;; Trigger classification

(def classify-trigger
  "Classify an event-stream event into a RoutingTriggerKind keyword.

   Args: a map carrying a `:type` key (standard event-stream envelope shape).
   Returns the matching RoutingTriggerKind keyword (one of
   `routing-trigger-kinds`), or `nil` when the event type is not a routing
   trigger. Pure — no side effects, no state."
  triggers/classify-trigger)

;------------------------------------------------------------------------------ Layer 1
;; Lifecycle — the only surface the rest of the workspace should touch.

(def start!
  "Start a correlator handle: subscribe to its stream, replay the published
   prefix, drain events that arrived during replay, then start the periodic
   expire ticker. Args: the handle returned by `create`/`attach!`. Returns
   the same handle. Idempotent — a second call on a started handle is a no-op.
   Side effects: stream subscription, edge emission on replay, scheduler
   thread."
  core/start!)

(def stop!
  "Stop a correlator handle: unsubscribe from the stream and cancel the
   expire ticker. Args: a handle. Returns the same handle. Idempotent.
   Retains the in-memory pure state so post-shutdown queries still read the
   edge indices."
  core/stop!)

(def attach!
  "Convenience constructor: `create` + `start!` in one step. Args: a `stream`,
   and an optional `deps` map (`:clock`, `:config`). Returns a started handle
   subscribed to `stream`. The test/wiring injection point."
  core/attach!)
