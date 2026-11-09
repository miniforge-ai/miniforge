;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-forward-dispatch
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-audit :as audit]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start-admission :as start]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-readiness :as readiness]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.run-control :as control]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refused [failure]
  {:effect/outcome :failed :effect/failure (msg/ts :experiment/native-start-refused)
   :effect/observed {:opsv/dispatch-refusal failure}})

(defn- ^{:stratum 0} authorized-call! [runtime issued ready record operation]
  (anomaly/let-ok [ctx (admission/current-context! runtime)
                  current (grant/current (:authority-directory runtime) (:grant/id issued))]
    (if (and (= (admission/snapshot ctx) (admission/snapshot (:context ready)))
             current
             (grant/authorized? (grant/authorize current {:effect/scope (:effect/proposal record) :usage/count 1}
                                                ((:clock runtime))))
             (not (control/stopped? (:control runtime))))
      (operation)
      (admission/refused))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} dispatch! [runtime issued prepared input record operation]
  (let [result (anomaly/let-ok [_committing (audit/record! (:context prepared) (:effects-directory runtime)
                                                        (:effect/id record) (:decision prepared))
                                ready (start/readiness! runtime input)
                                _retained (readiness/retain! ready record)]
                 (control/at-boundary! (:control runtime)
                   #(authorized-call! runtime issued ready record operation)))]
    (if (anomaly/anomaly? result) (refused result) result)))

(comment
  (admission/snapshot {}))
