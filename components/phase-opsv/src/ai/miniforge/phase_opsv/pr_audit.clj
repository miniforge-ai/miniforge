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
            [ai.miniforge.phase-opsv.pr-audit-model :as model]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unavailable []
  (anomaly/anomaly :unavailable (msg/ts :pr/audit-unavailable) {}))

(defn- ^{:stratum 0} event [ctx stream transaction]
  (let [data (model/payload transaction)
        workflow-id (context/workflow-id ctx)
        bundle-id (get-in transaction [:effect/proposal :opsv/evidence-bundle-id])
        expected (assoc data :event/type :opsv.actuation/disposition
                             :workflow/id workflow-id :opsv/evidence-bundle-id bundle-id)
        published (if (events/current-event-stream? stream) [] (events/get-events stream))]
    (if (anomaly/any-anomaly? published) published
      (or (model/matching-event expected (concat published (:opsv/disposition-events ctx)))
          (events/actuation-disposition stream workflow-id bundle-id data)))))

(defn- ^{:stratum 0} call-with-audit-failure [failure operation]
  (try+ (operation)
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          failure)
        (catch Object _ failure)))

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
      (let [draft (event ctx stream transaction)
            failure (call-with-audit-failure (unavailable) #(delivery/emit! ctx stream draft))]
        (some-> failure (assoc-in [:anomaly/data :opsv/disposition-event] draft)))
      (unavailable))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} record! [ctx transaction]
  (let [failure (call-with-audit-failure (unavailable) #(publish! ctx transaction))]
    (if failure
      (assoc-in failure [:anomaly/data :effect/transaction] transaction)
      transaction)))

(comment
  (ready? {} {}))
