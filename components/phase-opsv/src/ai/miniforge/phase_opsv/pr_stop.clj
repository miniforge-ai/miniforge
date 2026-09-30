;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-stop
  "Refuse and revoke when an admitted operation encounters a later stop."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.pr-model :as model]
            [ai.miniforge.phase-opsv.run-control :as control]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} stopped? [runtime]
  (or (true? (:stopped? (actuation/mutation-status (:fence runtime))))
      (when-let [handle (:control runtime)] (control/stopped? handle))))

(defn ^{:stratum 0} abandon! [runtime issued now result]
  (if-not (anomaly/anomaly? result)
    result
    (let [revoked (grant/revoke-stored! (:authority-directory runtime)
                                       (:grant/id issued) :revocation/superseded now)]
      (cond-> (assoc-in result [:anomaly/data :grant/id] (:grant/id issued))
        (anomaly/anomaly? revoked) (assoc-in [:anomaly/data :grant/revocation-failure] revoked)))))

(defn ^{:stratum 0} refusal [reason]
  {:effect/outcome :failed :effect/failure (msg/ts reason)})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} revoke! [runtime issued now]
  (let [revoked (grant/revoke-stored! (:authority-directory runtime)
                                     (:grant/id issued)
                                     (if (stopped? runtime) :revocation/operator :revocation/superseded) now)]
    (if (anomaly/anomaly? revoked)
      (assoc-in revoked [:anomaly/data :grant/id] (:grant/id issued))
      (anomaly/anomaly :unavailable (msg/ts :pr/stopped)
                       {:opsv/stopped? true :grant/id (:grant/id issued)}))))

(defn ^{:stratum 1} commit-result! [runtime issued id now result]
  (let [result (if (= :failed (:effect/state result)) (model/outcome nil result) result)]
  (if-not (anomaly/anomaly? result)
    result
    (let [current (effect/read-record (:effects-directory runtime) id)
          key (if (anomaly/anomaly? current) :effect/read-failure :effect/transaction)]
      (abandon! runtime issued now
                 (update result :anomaly/data assoc :effect/id id key current))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} settle!
  "Revoke using the last validated pre-dispatch instant; cleanup must not reread a failed clock."
  [runtime issued now result]
  (if-not (or (stopped? runtime) (= :failed (:effect/outcome result)))
    result
    (let [revoked (revoke! runtime issued now)]
      (if (true? (get-in revoked [:anomaly/data :opsv/stopped?]))
        result
        (-> result
            (assoc :effect/failure (msg/ts :pr/revocation-unconfirmed))
            (assoc-in [:effect/observed :grant/revocation-failure] (:anomaly/message revoked)))))))

(comment
  (stopped? {}))
