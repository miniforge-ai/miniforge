;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.gate-decision-test-support
  "One gated phase step through the real lifecycle and gate validation, with the
   DAG handoff and machine transition observed instead of run."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.execution-dag :as dag]
            [ai.miniforge.workflow.execution-step :as step]
            [ai.miniforge.workflow.execution-transition :as transition]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} output {:review/decision :approved})

(defn ^{:stratum 0} context [stream]
  (cond-> {:execution/id (random-uuid) :execution/status :running
           :execution/errors [] :execution/phase-results {}
           :execution/response-chain (response/create :gate-decision-test)}
    stream (assoc :event-stream stream)))

(defn ^{:stratum 0} legacy-stream []
  (events/create-event-stream {:sinks []}))

(defn ^{:stratum 0} decisions [stream]
  (filterv #(= :gate/decision (:event/type %)) (events/get-events stream)))

(defn- ^{:stratum 0} failed [ctx] (assoc ctx :execution/status :failed))

(defn- ^{:stratum 0} completed [ctx] (assoc ctx :execution/status :completed))

(defn- ^{:stratum 0} dag-handoff [calls takes-over? context & _args]
  (swap! calls conj :dag)
  (when takes-over? (assoc context :dag/took-over true)))

(defn- ^{:stratum 0} transitioned [calls context & _args]
  (swap! calls conj :transition)
  (assoc context :execution/transitioned true))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} enter-review [ctx]
  (assoc ctx :phase {:name :review :status :completed :started-at 0
                     :result {:status :success :output output}}))

(def ^{:stratum 1} ports {:merge-metrics merge :fail failed :complete completed})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} interceptor [gates]
  {:config {:phase :review :gates gates} :enter enter-review})

(defn ^{:stratum 2} execute
  "Return [context calls], where calls records the DAG handoff and the machine
   transition in the order they ran. When dag-takes-over? the handoff returns
   the context, as a plan whose tasks the DAG executor accepted does."
  [selected ctx dag-takes-over?]
  (let [calls (atom [])]
    (with-redefs [dag/try-dag-execution (partial dag-handoff calls dag-takes-over?)
                  transition/apply-phase-transition (partial transitioned calls)]
      [(step/execute-selected [selected] ctx {} selected ports) @calls])))

(comment
  (execute (interceptor [:review-approved]) (context (legacy-stream)) false))
