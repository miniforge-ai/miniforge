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

(ns ai.miniforge.automation-edge-correlator.triggers
  "Pure trigger-event classification.

   Maps incoming event-stream events to the correlator's RoutingTriggerKind
   taxonomy per the RFC at `docs/design/automation-edge-correlator.md`
   §RoutingTriggerKind taxonomy.

   No side effects. No state. All functions are pure.")

;------------------------------------------------------------------------------ Layer 0
;; Classification table — event-type → RoutingTriggerKind

(def ^:private trigger-table
  "Lookup map from event-stream `:type` keyword to RoutingTriggerKind.

   Mirrors the RFC §RoutingTriggerKind taxonomy table exactly:

   | Trigger event type                      | RoutingTriggerKind        |
   |-----------------------------------------|---------------------------|
   | :pr/merged                              | :pr-merged                |
   | :pr-monitor/review-comments-arrived     | :review-comments-arrived  |
   | :pr-monitor/ci-failed                   | :ci-failed                |
   | :standards-review/posted                | :standards-review-arrived |
   | :workflow/completed                     | :workflow-completed       |
   | :gate/passed                            | :gate-fired               |
   | :gate/failed                            | :gate-fired               |"
  {:pr/merged                              :pr-merged
   :pr-monitor/review-comments-arrived     :review-comments-arrived
   :pr-monitor/ci-failed                   :ci-failed
   :standards-review/posted                :standards-review-arrived
   :workflow/completed                     :workflow-completed
   :gate/passed                            :gate-fired
   :gate/failed                            :gate-fired})

(defn classify-trigger
  "Classify an event-stream event into a RoutingTriggerKind keyword.

   Accepts any map with a `:type` key (the standard event-stream envelope
   shape). Returns the matching RoutingTriggerKind keyword, or `nil` if the
   event type is not a routing trigger.

   Pure function. No side effects. No state."
  [event]
  (get trigger-table (:type event)))
