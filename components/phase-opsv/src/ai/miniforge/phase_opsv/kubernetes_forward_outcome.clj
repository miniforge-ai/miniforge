;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-forward-outcome
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} unconfirmed [result]
  (if (anomaly/anomaly? result)
    result
    (anomaly/anomaly :unavailable (msg/ts :experiment/native-start-unconfirmed) {:effect/transaction result})))

(defn ^{:stratum 0} failure [result audited]
  (cond
    (anomaly/anomaly? result) result
    (anomaly/anomaly? audited) audited
    (nil? result) (anomaly/anomaly :unavailable (msg/ts :experiment/native-start-unconfirmed) {})))

(defn ^{:stratum 0} with-record [failure id record result audited]
  (let [key (if (effect/valid? record) :effect/transaction :effect/read-failure)]
    (cond-> (update failure :anomaly/data assoc :effect/id id key record)
      (and (anomaly/anomaly? result) (anomaly/anomaly? audited))
      (assoc-in [:anomaly/data :opsv/audit-failure] audited))))

(comment
  (failure nil nil))
