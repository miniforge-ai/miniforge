;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-effect-audit-publication
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifact-confirmation :as confirmation]
            [ai.miniforge.phase-opsv.artifact-model :as artifacts]
            [ai.miniforge.phase-opsv.boundary.effect-disposition-event :as event]
            [ai.miniforge.phase-opsv.event-delivery :as delivery]
            [ai.miniforge.phase-opsv.exception-policy :as exceptions]
            [ai.miniforge.phase-opsv.kubernetes-effect-audit :as model]
            [ai.miniforge.phase-opsv.kubernetes-effect-audit-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failed [record]
  (anomaly/anomaly :unavailable (msg/ts :effect/native-audit-unavailable) {:effect/transaction record}))

(defn- ^{:stratum 0} retain! [ctx record decision]
  (let [output (model/material record decision)]
    (anomaly/let-ok [value (artifacts/record ctx output schema/material)]
      (confirmation/publish-with-exception-handling ctx value (artifacts/begin-publication output)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} emit! [ctx record decision]
  (let [bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        draft (event/draft ctx (context/stream ctx) bundle-id (model/payload record decision))
        failure (try+ (delivery/emit! ctx (context/stream ctx) draft)
                      (catch InterruptedException interrupted
                        (.interrupt (Thread/currentThread)) (throw interrupted))
                      (catch exceptions/nonfatal? _ (failed record)))]
    (if (anomaly/anomaly? failure)
      (assoc-in failure [:anomaly/data :opsv/disposition-event] draft)
      record)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish! [ctx record decision]
  (try+
    (let [result (anomaly/let-ok [_retained (retain! ctx record decision)] (emit! ctx record decision))]
      (if (anomaly/anomaly? result)
        (assoc-in result [:anomaly/data :effect/transaction] record)
        result))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch exceptions/nonfatal? _ (failed record))))

(comment
  (model/payload {} {}))
