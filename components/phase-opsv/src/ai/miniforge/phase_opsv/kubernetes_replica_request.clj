;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-request
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.kubernetes-preparation :as preparation]
            [ai.miniforge.phase-opsv.kubernetes-read-plan :as reads]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private media-type "RFC 6902, not strategic merge or server-side apply." "application/json-patch+json")

(def ^{:stratum 0} ^:private uid-path "Fence replacement objects even when a name is reused." "/metadata/uid")

(def ^{:stratum 0} ^:private revision-path "Fence changes made since the fresh capture." "/metadata/resourceVersion")

(def ^{:stratum 0} ^:private replicas-path "The only mutable field supported by this request builder." "/spec/replicas")

(defn- ^{:stratum 0} patch-operation [operation path value]
  {"op" operation
   "path" path
   "value" value})

(defn- ^{:stratum 0} resource-reference [deployment]
  (let [namespace (resource/namespace-of deployment)
        name (resource/name-of deployment)
        uid (resource/uid deployment)]
    {:api-version "apps/v1"
     :kind "Deployment"
     :namespace namespace
     :name name
     :uid uid}))

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

(defn- ^{:stratum 1} payload [declaration deployment desired]
  (let [namespace (resource/namespace-of deployment)
        name (:kubernetes/deployment-name declaration)
        path (first (reads/request "Deployment" namespace false name))
        uid (resource/uid deployment)
        revision (get-in deployment ["metadata" "resourceVersion"])
        body [(patch-operation "test" uid-path uid)
              (patch-operation "test" revision-path revision)
              (patch-operation "replace" replicas-path desired)]]
    {:method :patch
     :path path
     :media-type media-type
     :body body}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [declaration request baseline current parameters]
  (let [original (get-in baseline [:environment/raw-observations :kubernetes/verification :kubernetes/source
                                   :kubernetes/inventory :kubernetes/deployment])
        deployment (get-in current [:kubernetes/inventory :kubernetes/deployment])
        operation (:opsv/operation parameters)
        desired (desired-count original parameters)
        payload (payload declaration deployment desired)
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
