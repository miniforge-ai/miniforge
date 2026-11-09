;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-start-transaction
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-audit :as audit]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start-dispatch :as dispatch]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start-provider :as native]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} audit! [runtime prepared id]
  (audit/record! (:context prepared) (:effects-directory runtime) id (:decision prepared)))

(defn- ^{:stratum 0} commit! [runtime prepared input issued id]
  (let [operation (fn [record]
                    (native/patch! {:context (:context prepared) :input input} record
                                   (partial dispatch/dispatch! runtime issued prepared input record)))]
    (effect/commit-current! (:effects-directory runtime) id (:grant/id issued)
                            (partial grant/current (:authority-directory runtime)) (:clock runtime) operation)))

(defn- ^{:stratum 0} available-record! [runtime id result audited]
  (let [retained (get-in audited [:anomaly/data :effect/transaction])]
    (cond
      (not (anomaly/anomaly? audited)) audited
      retained retained
      (effect/valid? result) result
      :else (effect/read-record (:effects-directory runtime) id))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} record-result! [runtime prepared id result]
  (let [audited (audit! runtime prepared id)
        failure (cond (anomaly/anomaly? result) result
                      (anomaly/anomaly? audited) audited
                      (nil? result) (anomaly/anomaly :unavailable (msg/ts :experiment/native-start-unconfirmed) {}))]
    (if-not failure audited
      (let [record (available-record! runtime id result audited)
            key (if (effect/valid? record) :effect/transaction :effect/read-failure)]
        (cond-> (update failure :anomaly/data assoc :effect/id id key record)
          (and (anomaly/anomaly? result) (anomaly/anomaly? audited))
          (assoc-in [:anomaly/data :opsv/audit-failure] audited))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute! [runtime prepared input issued now]
  (let [proposal (:proposal prepared)
        id (:effect/id proposal)
        options {:effect-id id :effect-class (:effect/class proposal) :proposal proposal
                 :grant-id (:grant/id issued) :envelope-id (get-in prepared [:decision :envelope/id])}]
    (anomaly/let-ok [_proposed (effect/propose! (:effects-directory runtime) options now)
                    _audited (audit! runtime prepared id)]
      (record-result! runtime prepared id (commit! runtime prepared input issued id)))))

(comment
  (get-in {:decision {:envelope/id (random-uuid)}} [:decision :envelope/id]))
