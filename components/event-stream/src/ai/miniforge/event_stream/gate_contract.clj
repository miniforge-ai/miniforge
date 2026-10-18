;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.gate-contract
  "Cross-field invariants for N3 gate evidence; authority remains runtime-owned."
  (:require [ai.miniforge.decision-envelope.interface :as decision]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} phase-agrees? [event]
  (let [phase (:workflow/phase event)]
    (or (nil? phase) (= phase (:gate/phase event)))))

(defn ^{:stratum 0} decision-agrees? [event]
  (let [envelope (:gate/decision-envelope event)
        derived (decision/derive-decision (:envelope/reasons envelope) (:envelope/obligations envelope))]
    (and (= (:envelope/id event) (:envelope/id envelope))
         (= derived (:envelope/decision envelope)))))

(defn ^{:stratum 0} violation-agrees? [event]
  (or (nil? (:rule/violation event)) (= :failed (:rule/status event))))

(comment
  (phase-agrees? {:gate/phase :implement}))
