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
   phase. The gate decision returns its acknowledgment or an anomaly,
   because a gated phase may not advance on a decision that was not
   recorded. The DAG publishers swallow their own failures —
   observability must not break execution.

   Split out of `execution` (rule 210), which held these alongside the
   phase lifecycle, gate/transition logic and DAG integration as one
   six-stratum file."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.messages :as messages]
            [slingshot.slingshot :refer [try+]]))

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

(defn- ^{:stratum 0} resolve-phase
  "Phase maps built by `phase/enter-context` carry their name under :name;
   the active phase identity is the fallback."
  [ctx phase-result]
  (or (:phase/name phase-result) (:name phase-result) (:phase phase-result)
      (:execution/current-phase ctx)))

(defn- ^{:stratum 0} nonfatal? [value]
  (not (instance? Error value)))

(defn- ^{:stratum 0} decision-failure [reason data]
  (anomaly/anomaly :unavailable (messages/t :gate/decision-unacknowledged)
                   (assoc data :gate/publication-reason reason)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} acknowledged-decision
  "Return the receipt only when it is this decision at an assigned stream position.
   A current stream returns it after a durable journal commit. A legacy stream
   returns the event it delivered; its sinks swallow write failures, so a legacy
   receipt shows delivery to the stream, not persistence."
  [event receipt]
  (let [identity-fields [:event/id :event/type :event/version :workflow/id :scope/type
                         :gate/phase :envelope/id :gate/decision-envelope]]
    (cond
      (anomaly/any-anomaly? receipt) receipt
      (and (map? receipt) (not (:rejected? receipt))
           (= (select-keys event identity-fields) (select-keys receipt identity-fields))
           (nat-int? (:event/sequence-number receipt))
           (or (not (contains? event :event/sequence-number))
               (= (:event/sequence-number event) (:event/sequence-number receipt)))) receipt
      :else (decision-failure :unconfirmed (select-keys event [:event/id :envelope/id])))))

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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} emit-phase-decision!
  "Publish the :gate/decision event for a gated transition. Returns the
   acknowledged receipt, or an anomaly when there is no envelope or no event
   stream, when construction or publication returns an anomaly, when the
   receipt is rejected or does not match the decision, or when publication
   throws. Errors propagate; an interrupt is restored on the thread. Stream
   and workflow id come from the same resolvers as phase telemetry."
  [ctx phase-result envelope]
  (let [stream (resolve-event-stream ctx)]
    (cond
      (nil? envelope) (decision-failure :missing-envelope {})
      (nil? stream) (decision-failure :missing-stream {})
      :else
      (try+
        (let [event (events/phase-decision stream (resolve-workflow-id ctx)
                                           (resolve-phase ctx phase-result) envelope)]
          (if (anomaly/any-anomaly? event)
            event
            (acknowledged-decision event (events/publish! stream event))))
        (catch anomaly/any-anomaly? failure failure)
        #_{:clj-kondo/ignore [:unresolved-symbol]}
        (catch nonfatal? _
          (let [thrown (:throwable &throw-context)]
            (when (instance? InterruptedException thrown)
              (.interrupt (Thread/currentThread)))
            (decision-failure :exception {:error (ex-message thrown)})))))))
