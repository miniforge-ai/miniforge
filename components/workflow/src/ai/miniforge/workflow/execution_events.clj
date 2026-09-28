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
(ns ai.miniforge.workflow.execution-events
  "Events the phase executor publishes: the gate decision for a gated
   transition, and whether the DAG executor took over after the plan
   phase. Every publisher swallows its own failures — observability must
   not break execution.

   Split out of `execution` (rule 210), which held these alongside the
   phase lifecycle, gate/transition logic and DAG integration as one
   six-stratum file."
  (:require [ai.miniforge.event-stream.interface :as events]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resolve-event-stream
  "Find the event stream from context, matching phase/telemetry's lookup."
  [ctx]
  (or (:event-stream ctx)
      (:execution/event-stream ctx)
      (get-in ctx [:execution/opts :event-stream])))

(defn- ^{:stratum 0} resolve-workflow-id
  [ctx]
  (or (:execution/id ctx) (:workflow/id ctx) (:workflow-id ctx)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} emit-phase-decision!
  "Publish the :gate/decision event for a gated transition; never breaks
   enforcement. Stream and workflow id come from the canonical resolvers
   (same lookup as phase telemetry), so contexts carrying the stream under
   :execution/opts publish too."
  [ctx phase-result envelope]
  (try
    (when-let [stream (resolve-event-stream ctx)]
      (when envelope
        (events/publish! stream
                         (events/phase-decision stream (resolve-workflow-id ctx)
                                                (or (:phase/name phase-result)
                                                    (:phase phase-result))
                                                envelope))))
    (catch Exception _ nil))
  nil)

(defn ^{:stratum 1} emit-dag-considered!
  "Emit a :workflow/dag-considered event describing whether DAG fired and why.
   Swallows errors — observability must not break execution."
  [ctx outcome reason extra]
  (when-let [stream (resolve-event-stream ctx)]
    (try
      (let [event (merge
                    {:event/type :workflow/dag-considered
                     :event/timestamp (str (java.time.Instant/now))
                     :workflow/id (resolve-workflow-id ctx)
                     :dag/outcome outcome
                     :dag/reason reason}
                    extra)]
        (events/publish! stream event))
      (catch Exception _ nil))))

(defn ^{:stratum 1} emit-dag-activated!
  "Emit :workflow/dag-activated when the DAG orchestrator takes over.

   Distinct event type from :workflow/dag-considered so consumers can
   filter on a single keyword to see every DAG activation without parsing
   the outcome field.  Swallows errors — observability must not break execution."
  [ctx plan]
  (when-let [stream (resolve-event-stream ctx)]
    (try
      (events/publish! stream {:event/type      :workflow/dag-activated
                               :event/timestamp (str (java.time.Instant/now))
                               :workflow/id     (resolve-workflow-id ctx)
                               :plan/id         (:plan/id plan)
                               :plan/task-count (count (:plan/tasks plan))})
      (catch Exception _ nil))))

(defn ^{:stratum 1} emit-dag-skipped!
  "Emit :workflow/dag-skipped when DAG execution is not attempted.

   Distinct event type from :workflow/dag-considered so consumers can
   filter on a single keyword to see every DAG skip without parsing the
   outcome field.  Swallows errors — observability must not break execution."
  [ctx reason extra]
  (when-let [stream (resolve-event-stream ctx)]
    (try
      (events/publish! stream (merge {:event/type      :workflow/dag-skipped
                                      :event/timestamp (str (java.time.Instant/now))
                                      :workflow/id     (resolve-workflow-id ctx)
                                      :dag/reason      reason}
                                     extra))
      (catch Exception _ nil))))
