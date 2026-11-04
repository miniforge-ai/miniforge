;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-inventory-fixtures
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-membership-fixtures :as membership]
            [ai.miniforge.phase-opsv.kubernetes-settings-test-support :as settings]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} namespace-path "/api/v1/namespaces/staging")

(def ^{:stratum 0} deployment-path "/apis/apps/v1/namespaces/staging/deployments/catalog")

(def ^{:stratum 0} replica-sets-path "/apis/apps/v1/namespaces/staging/replicasets")

(def ^{:stratum 0} pods-path "/api/v1/namespaces/staging/pods")

(defn ^{:stratum 0} declaration []
  {:runtime/target (membership/target) :kubernetes/deployment-name "catalog"})

(defn ^{:stratum 0} observe! [routes overrides declared]
  (let [calls (atom [])
        base (dissoc (settings/settings) :kubernetes/pod-selector :kubernetes/node-pool-label)]
    (with-redefs [transport/start! (partial wire/reply! routes calls)
                  transport/response-fields identity]
      [(phase/observe-kubernetes-deployment-inventory! (merge base overrides) declared) @calls])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} routes []
  (let [observed (membership/observation)
        metadata {"resourceVersion" "42" "continue" ""}]
    {namespace-path (:kubernetes/namespace observed)
     deployment-path (:kubernetes/deployment observed)
     replica-sets-path {"apiVersion" "apps/v1" "kind" "ReplicaSetList"
                        "metadata" metadata "items" (:kubernetes/replica-sets observed)}
     pods-path (assoc (:kubernetes/pods observed) "metadata" metadata)}))

(defn ^{:stratum 1} capture! [routes overrides]
  (observe! routes overrides (declaration)))

(comment ::bounded-namespace-inventory-fixtures-with-no-live-endpoints)
