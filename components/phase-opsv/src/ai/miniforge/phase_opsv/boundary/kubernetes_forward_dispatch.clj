;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-forward-dispatch
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-audit :as audit]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-authorization :as authority]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-readiness :as readiness]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.run-control :as control]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refused [failure]
  {:effect/outcome :failed
   :effect/failure (msg/ts :experiment/native-start-refused)
   :effect/observed {:opsv/dispatch-refusal failure}})

(defn- ^{:stratum 0} call-unless-stopped! [runtime operation]
  (if (control/stopped? (:control runtime)) (admission/refused) (operation)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} invoke! [runtime issued ready record operation]
  (let [current-operation (partial call-unless-stopped! runtime operation)]
    (control/at-boundary! (:control runtime)
      (partial authority/call! admission/current-context! runtime issued ready record current-operation))))

(defn ^{:stratum 1} dispatch! [kind runtime issued prepared input record operation]
  (let [result (anomaly/let-ok [_committing (audit/record! (:context prepared) (:effects-directory runtime)
                                                        (:effect/id record) (:decision prepared))
                                observed ((:readiness kind) runtime input)
                                ready ((:admit kind) (assoc observed :proposal (:effect/proposal record)))
                                _retained (readiness/retain! kind ready record)]
                 ((:invoke kind) runtime issued ready record operation))]
    (if (anomaly/anomaly? result) (refused result) result)))

(comment
  (admission/snapshot {}))
