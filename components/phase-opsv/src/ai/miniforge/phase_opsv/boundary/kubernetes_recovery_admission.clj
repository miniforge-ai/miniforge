;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-recovery-admission
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.decision-envelope.interface :as decision]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as forward]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-context :as context]
            [ai.miniforge.phase-opsv.boundary.recovery-plan-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.retained-recovery-plan :as retained]
            [ai.miniforge.phase-opsv.kubernetes-effect-readiness :as readiness]
            [ai.miniforge.phase-opsv.kubernetes-recovery-effect :as identity]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} allowing? (m/validator decision/AllowingEnvelope))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} readiness! [runtime input]
  (anomaly/let-ok [ctx (context/current! runtime)
                  reference (retained/read! ctx (:recovery/input input) (:recovery/artifact-ref input))
                  admitted (admission/admit! ctx (:recovery/input input))]
    (if (and (allowing? (:recovery/plan-decision-envelope admitted))
             (= (:recovery/hash reference) (:recovery/hash admitted)))
      (readiness/admitted ctx (:recovery/plan-decision-envelope admitted) reference)
      (forward/refused))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} restoration-readiness! [runtime input]
  (anomaly/let-ok [admitted (readiness! runtime input)]
    (if (identity/matches? input admitted :restore-baseline)
      admitted
      (forward/refused))))

(comment ::fresh-independent-allowing-decision-does-not-reopen-forward-writes)
