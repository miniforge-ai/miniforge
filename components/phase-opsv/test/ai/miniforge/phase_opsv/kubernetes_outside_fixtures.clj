;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-outside-fixtures
  (:require [ai.miniforge.phase-opsv.kubernetes-replica-fixtures :as replicas]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} input []
  (merge (replicas/input)
         (select-keys (kube/snapshot) [:kubernetes/cluster :kubernetes/configuration])))

(defn- ^{:stratum 0} in-namespace [namespace value]
  (assoc-in value ["metadata" "namespace"] namespace))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} cluster-namespace-input []
  (let [captured (input)
        cluster (:kubernetes/cluster captured)
        namespace (get-in cluster ["metadata" "name"])
        uid (get-in cluster ["metadata" "uid"])]
    (-> captured
        (assoc-in [:kubernetes/inventory :kubernetes/namespace] cluster)
        (update-in [:kubernetes/inventory :runtime/target] assoc :namespace namespace :namespace-uid uid)
        (update-in [:kubernetes/inventory :kubernetes/deployment] (partial in-namespace namespace))
        (update-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items"] (partial mapv (partial in-namespace namespace)))
        (update-in [:kubernetes/inventory :kubernetes/pod-inventory "items"] (partial mapv (partial in-namespace namespace)))
        (update :kubernetes/configuration (partial mapv (partial in-namespace namespace))))))
