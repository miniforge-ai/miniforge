;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.execution-step-support
  "Instrumented lifecycle and transition ports for phase-boundary assertions."
  (:require [ai.miniforge.workflow.execution-step :as step]
            [ai.miniforge.workflow.execution-dag :as dag]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [ai.miniforge.workflow.execution-transition :as transition]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} interceptor {:config {:phase :plan}})

(def ^{:stratum 0} phase-result {:status :completed})

(defn- ^{:stratum 0} failed [context] (assoc context :execution/status :failed))

(defn- ^{:stratum 0} completed [context] (assoc context :execution/status :completed))

(defn- ^{:stratum 0} observe [calls action value & _args]
  (swap! calls conj action)
  value)

(defn- ^{:stratum 0} processed [context phase result _merge-metrics]
  (assoc-in context [:execution/phase-results phase] result))

(defn- ^{:stratum 0} context []
  {:execution/status :running :execution/errors [] :execution/phase-results {}})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ports {:merge-metrics merge :fail failed :complete completed})

(defn- ^{:stratum 1} entered [calls _interceptor context]
  (swap! calls conj :enter)
  [(assoc context :effect/retained true) phase-result])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute [start-result completion-result dag-result]
  (let [calls (atom [])
        callbacks {:on-phase-start (partial observe calls :start start-result)
                   :on-phase-complete (partial observe calls :complete completion-result)}]
    (with-redefs [lifecycle/execute-phase-lifecycle (partial entered calls)
                  lifecycle/process-phase-result processed
                  dag/try-dag-execution (partial observe calls :dag dag-result)
                  transition/determine-phase-event (constantly :phase/succeeded)
                  transition/apply-phase-transition (partial observe calls :transition :advanced)]
      [(step/execute-selected [interceptor] (context) callbacks interceptor ports) @calls])))
