;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-partition-fixtures
  (:require [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-membership-fixtures :as membership]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} outside-resources []
  (let [parent (-> (membership/replica-set "outside" "outside-rs")
                  (assoc-in ["metadata" "ownerReferences" 0 "uid"] "outside-deployment"))]
    [parent (membership/pod "outside-pod" "outside-pod-uid" parent)]))

(defn ^{:stratum 0} observation []
  (let [routes (inventory/routes)
        declared (inventory/declaration)]
    {:runtime/target (:runtime/target declared)
     :kubernetes/cluster-id "staging-cluster"
     :kubernetes/namespace (get routes inventory/namespace-path)
     :kubernetes/deployment (get routes inventory/deployment-path)
     :kubernetes/replica-set-inventory (get routes inventory/replica-sets-path)
     :kubernetes/pod-inventory (get routes inventory/pods-path)}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-outside-resources []
  (let [[parent pod] (outside-resources)]
    (-> (observation)
        (update-in [:kubernetes/replica-set-inventory "items"] conj parent)
        (update-in [:kubernetes/pod-inventory "items"] conj pod))))

(comment ::raw-inventory-and-independently-named-outside-controller-chain)
