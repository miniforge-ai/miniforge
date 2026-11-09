;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-forward-transaction
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-audit :as audit]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-dispatch :as dispatch]
            [ai.miniforge.phase-opsv.kubernetes-forward-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} audit! [runtime prepared id]
  (audit/record! (:context prepared) (:effects-directory runtime) id (:decision prepared)))

(defn- ^{:stratum 0} invoke! [kind runtime prepared input issued record]
  (let [dispatch (partial dispatch/dispatch! kind runtime issued prepared input record)]
    ((:native kind) (:context prepared) input record dispatch)))

(defn- ^{:stratum 0} available-record! [runtime id result audited]
  (let [retained (get-in audited [:anomaly/data :effect/transaction])]
    (cond
      (not (anomaly/anomaly? audited)) audited
      retained retained
      (effect/valid? result) result
      :else (effect/read-record (:effects-directory runtime) id))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} commit! [kind runtime prepared input issued id]
  (let [operation (partial invoke! kind runtime prepared input issued)]
    (effect/commit-current! (:effects-directory runtime) id (:grant/id issued)
                            (partial grant/current (:authority-directory runtime)) (:clock runtime) operation)))

(defn- ^{:stratum 1} record-result! [runtime prepared id result]
  (let [audited (audit! runtime prepared id)
        failure (outcome/failure result audited)]
    (if failure
      (outcome/with-record failure id (available-record! runtime id result audited) result audited)
      audited)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute! [kind runtime prepared input issued now]
  (let [proposal (:proposal prepared)
        id (:effect/id proposal)
        options {:effect-id id
                 :effect-class (:effect/class proposal)
                 :proposal proposal
                 :grant-id (:grant/id issued)
                 :envelope-id (get-in prepared [:decision :envelope/id])}]
    (anomaly/let-ok [_proposed (effect/propose! (:effects-directory runtime) options now)
                    _audited (audit! runtime prepared id)]
      (record-result! runtime prepared id (commit! kind runtime prepared input issued id)))))

(comment
  (get-in {:decision {:envelope/id (random-uuid)}} [:decision :envelope/id]))
