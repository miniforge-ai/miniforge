;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-stop
  "Refuse and revoke when an admitted operation encounters a later stop."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} stopped? [runtime]
  (true? (:stopped? (actuation/mutation-status (:fence runtime)))))

(defn ^{:stratum 0} abandon! [runtime issued now result]
  (if-not (anomaly/anomaly? result)
    result
    (let [revoked (grant/revoke-stored! (:authority-directory runtime)
                                       (:grant/id issued) :revocation/superseded now)]
      (cond-> (assoc-in result [:anomaly/data :grant/id] (:grant/id issued))
        (anomaly/anomaly? revoked) (assoc-in [:anomaly/data :grant/revocation-failure] revoked)))))

(defn ^{:stratum 0} revoke! [runtime issued now]
  (let [revoked (grant/revoke-stored! (:authority-directory runtime)
                                     (:grant/id issued) :revocation/operator now)]
    (if (anomaly/anomaly? revoked)
      revoked
      (anomaly/anomaly :unavailable (msg/ts :pr/stopped)
                       {:opsv/stopped? true :grant/id (:grant/id issued)}))))

(defn ^{:stratum 0} refusal [reason]
  {:effect/outcome :failed :effect/failure (msg/ts reason)})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} settle! [runtime issued result]
  (if-not (stopped? runtime)
    result
    (let [revoked (revoke! runtime issued ((:clock runtime)))]
      (if (true? (get-in revoked [:anomaly/data :opsv/stopped?]))
        result
        (-> result
            (assoc :effect/failure (msg/ts :pr/revocation-unconfirmed))
            (assoc-in [:effect/observed :grant/revocation-failure] (:anomaly/message revoked)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} refuse! [runtime issued]
  (settle! runtime issued (refusal :pr/stopped)))

(comment
  (stopped? {}))
