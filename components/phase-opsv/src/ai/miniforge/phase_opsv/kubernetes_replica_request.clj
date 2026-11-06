;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-request
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.kubernetes-preparation :as preparation]
            [ai.miniforge.phase-opsv.kubernetes-replica-payload :as wire]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private resource-reference wire/resource-reference)

(def ^{:stratum 0} ^:private payload wire/prepare)

(defn- ^{:stratum 0} target-bound? [declaration capture]
  (let [target (:runtime/target declaration)
        inventory (:kubernetes/inventory capture)
        deployment (:kubernetes/deployment inventory)]
    (and (= target (:runtime/target inventory))
         (= (:cluster target) (:kubernetes/cluster-id inventory))
         (= (:namespace-uid target) (resource/uid (:kubernetes/namespace inventory)))
         (= (:workload-uid target) (resource/uid deployment))
         (= (:namespace target) (resource/namespace-of deployment))
         (= (:kubernetes/deployment-name declaration) (resource/name-of deployment)))))

(defn- ^{:stratum 0} desired-count [original parameters]
  (if (= :restore-baseline (:opsv/operation parameters))
    (get-in original ["spec" "replicas"])
    (:replicas/count parameters)))

(defn- ^{:stratum 0} within? [bounds count]
  (<= (:replicas/min-count bounds) count (:replicas/max-count bounds)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} count-bounded? [request original current desired operation]
  (let [original-count (get-in original ["spec" "replicas"])
        current-count (get-in current ["spec" "replicas"])
        contract (get-in request [:comparison/pin :comparison/contract :comparison/replica-contract])
        stage (if (= :trial-apply operation) :candidate :rollback)
        bounds (get contract stage)
        recovery (:rollback contract)
        delta (get-in request [:experiment-pack :experiment-pack/guardrails :blast-radius :replica-delta])]
    (and (within? recovery original-count)
         (within? bounds desired)
         (or (within? recovery current-count) (within? (:candidate contract) current-count))
         (<= (abs (- desired current-count)) delta)
         (<= (abs (- desired original-count)) delta))))

(defn- ^{:stratum 1} request-bound? [declaration request baseline current]
  (and (preparation/correlated? declaration request)
       (= (:runtime/target declaration) (:runtime/target baseline))
       (= (get-in request [:comparison/pin :comparison/contract :comparison/baseline])
          (:environment-fingerprint baseline))
       (target-bound? declaration (get-in baseline [:environment/raw-observations :kubernetes/verification :kubernetes/source]))
       (target-bound? declaration current)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [declaration request baseline current parameters]
  (let [original (get-in baseline [:environment/raw-observations :kubernetes/verification :kubernetes/source
                                   :kubernetes/inventory :kubernetes/deployment])
        deployment (get-in current [:kubernetes/inventory :kubernetes/deployment])
        operation (:opsv/operation parameters)
        desired (desired-count original parameters)
        payload (payload deployment desired)
        digest (hash/content-hash payload)
        resources [(resource-reference deployment)]
        target (:runtime/target declaration)]
    (if (and (request-bound? declaration request baseline current)
             (count-bounded? request original deployment desired operation))
      {:effect/class :effect/deploy
       :opsv/operation operation
       :runtime/target target
       :opsv/resources resources
       :opsv/payload-hash digest
       :kubernetes/request payload}
      (anomaly/anomaly :conflict (msg/ts :replicas/request-mismatch) {}))))

(comment ::canonical-data-only-no-retention-authority-provider-or-recovery-proof)
