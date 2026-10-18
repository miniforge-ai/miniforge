;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.gate-projection
  "Pure phase-result projections for gate decisions and publication failures."
  (:require [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} gate-failure [result]
  (-> (select-keys result [:gate :errors]) (update :errors (fnil vec []))))

(defn- ^{:stratum 0} failed-phase [phase-result]
  (-> phase-result
      (assoc :status :failed :phase/status :failed)
      (assoc-in [:result :status] :error)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publication-failure [phase-result failure]
  (-> (failed-phase phase-result)
      (assoc :phase/gate-publication-failure failure)
      (update :phase/gate-errors (fnil conj []) (messages/t :gate/publication-unconfirmed))))

(defn- ^{:stratum 1} denied [phase-result gate-result envelope]
  (let [errors (vec (if envelope (:envelope/reasons envelope) (:failed-gates gate-result)))
        failures (into [] (comp (remove gate/passed?) (map gate-failure)) (:results gate-result))]
    (assoc (failed-phase phase-result) :phase/decision-envelope envelope
                                      :phase/gate-errors errors :phase/gate-failures failures)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} decision [phase-result gate-result envelope]
  (if (and (:passed? gate-result) envelope (gate/decision-allowed? envelope))
    (assoc phase-result :phase/decision-envelope envelope)
    (denied phase-result gate-result envelope)))

(comment
  (publication-failure {} {}))
