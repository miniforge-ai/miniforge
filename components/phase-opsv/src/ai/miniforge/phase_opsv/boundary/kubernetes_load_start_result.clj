;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-start-result
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.effect-authority :as authority]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.run-control :as control]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [slingshot.slingshot :refer [try+ throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} fence! [runtime input]
  (when (and (control/run? (:control runtime))
             (control/bound-to? (:control runtime) (get-in input [:recovery/input :load :declaration :workflow/id])
                               (:authority-directory runtime) (:fence runtime)))
    (actuation/stop-mutations! (:fence runtime))))

(defn- ^{:stratum 0} revoke! [runtime issued now]
  (let [revoked (grant/revoke-stored! (:authority-directory runtime) (:grant/id issued) :revocation/superseded now)]
    (when (anomaly/anomaly? revoked) (throw+ revoked))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} finish! [runtime input issued now result]
  (if (= :succeeded (:effect/state result))
    result
    (do
      (fence! runtime input)
      (authority/abandon! runtime issued now
                          (if (anomaly/anomaly? result) result
                              (anomaly/anomaly :unavailable (msg/ts :experiment/native-start-unconfirmed)
                                               {:effect/transaction result}))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} complete! [runtime input issued now operation]
  (try+ (finish! runtime input issued now (operation))
    (catch Object _
      (fence! runtime input)
      (cleanup/after-failure! (:throwable &throw-context) (partial revoke! runtime issued now)))))

(comment
  (fence! {} {}))
