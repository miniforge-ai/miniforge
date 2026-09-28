;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-audit
  "Record durable PR dispositions before mutation and after every known outcome."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.event-delivery :as delivery]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.pr-model :as model]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unavailable []
  (anomaly/anomaly :unavailable (msg/ts :pr/audit-unavailable) {}))

(defn- ^{:stratum 0} event [ctx stream transaction]
  (events/actuation-disposition
   stream (context/workflow-id ctx) (get-in transaction [:effect/proposal :opsv/evidence-bundle-id])
   {:opsv/governed-effect (model/governed-effect transaction)
    :opsv/effect-state (:effect/state transaction)
    :opsv/decision-envelope (get-in transaction [:effect/proposal :opsv/envelope])
    :opsv/effect-observed (select-keys (:effect/observed transaction) [:pr/url :pr/number :pr/head-sha])
    :opsv/effect-failure (:effect/failure transaction)}))

(defn- ^{:stratum 0} ready? [ctx transaction]
  (let [bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        store (:opsv/evidence-assembly-store ctx)
        assembly (when store (evidence/get-opsv-assembly store bundle-id))]
    (and (effect/valid? transaction)
         (= bundle-id (get-in transaction [:effect/proposal :opsv/evidence-bundle-id]))
         (= (:effect/envelope-id transaction)
            (get-in transaction [:effect/proposal :opsv/envelope :envelope/id]))
         (= (context/workflow-id ctx) (get-in transaction [:effect/proposal :workflow-run/id]))
         (= (context/workflow-id ctx) (:evidence-bundle/workflow-id assembly))
         (= :assembling (:opsv.assembly/status assembly)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish! [ctx transaction]
  (let [stream (context/stream ctx)]
    (if (and stream (ready? ctx transaction))
      (delivery/emit! ctx stream (event ctx stream transaction))
      (unavailable))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} record! [ctx transaction]
  (let [failure (try (publish! ctx transaction)
                     (catch InterruptedException _
                       (let [failure (unavailable)]
                         (.interrupt (Thread/currentThread))
                         failure))
                     (catch Exception _
                       (unavailable)))]
    (if failure
      (assoc-in failure [:anomaly/data :effect/transaction] transaction)
      transaction)))

(comment
  (ready? {} {}))
