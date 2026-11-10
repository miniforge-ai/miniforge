;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-recovery-effect-admission
  "API surface class 1: current admission of code-owned recovery proposals."
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as forward]
            [ai.miniforge.phase-opsv.governance-evaluation :as evaluation]
            [ai.miniforge.phase-opsv.recovery-effect-gate]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} admit! [ready]
  (let [ctx (:context ready)
        policy (get-in ctx [:execution/opts :opsv/governance])
        policy (update policy :policy/context assoc :opsv/recovery-plan (:recovery/plan ready)
                       :opsv/recovery-ownership (:ownership/observation ready))
        evaluated (evaluation/evaluate [:recovery-effect] ctx (:proposal ready) {} policy)
        decision (:opsv/decision-envelope evaluated)
        evidence (assoc evaluated :effect/proposal (:proposal ready)
                        :recovery/hash (:recovery/hash ready) :recovery/plan (:recovery/plan ready))
        evidence (cond-> evidence (contains? ready :ownership/observation)
                         (assoc :ownership/observation (:ownership/observation ready)))]
    (if (= :allow (:envelope/decision decision))
      (assoc ready :decision decision :recovery/effect-admission evidence)
      (assoc-in (forward/refused) [:anomaly/data :recovery/effect-admission] evaluated))))

(comment ::current-exact-effect-decision-before-issuance-and-again-inside-dispatch)
