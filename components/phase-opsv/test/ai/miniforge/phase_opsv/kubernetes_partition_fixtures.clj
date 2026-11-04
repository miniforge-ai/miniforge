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

(defn- ^{:stratum 0} without-types [items]
  (mapv #(dissoc % "apiVersion" "kind") items))

(defn ^{:stratum 0} capture-lists! [raw]
  (let [routes (assoc (inventory/routes)
                     inventory/replica-sets-path (:kubernetes/replica-set-inventory raw)
                     inventory/pods-path (:kubernetes/pod-inventory raw))]
    (inventory/capture! routes {})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-outside-resources []
  (let [[parent pod] (outside-resources)]
    (-> (observation)
        (update-in [:kubernetes/replica-set-inventory "items"] conj parent)
        (update-in [:kubernetes/pod-inventory "items"] conj pod))))

(defn ^{:stratum 1} without-item-types []
  (-> (observation)
      (update-in [:kubernetes/replica-set-inventory "items"] without-types)
      (update-in [:kubernetes/pod-inventory "items"] without-types)))

(defn ^{:stratum 1} unmanaged-first [collection]
  (update-in (observation) [collection "items" 0 "metadata"] dissoc "ownerReferences"))

(defn ^{:stratum 1} empty-inventory []
  (-> (observation)
      (assoc-in [:kubernetes/replica-set-inventory "items"] [])
      (assoc-in [:kubernetes/pod-inventory "items"] [])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} pending-member-with-matching-outside-labels []
  (-> (with-outside-resources)
      (assoc-in [:kubernetes/pod-inventory "items" 2 "metadata" "labels"] {"app" "catalog"})
      (update-in [:kubernetes/pod-inventory "items" 0 "spec"] dissoc "nodeName")
      (assoc-in [:kubernetes/pod-inventory "items" 0 "status" "phase"] "Pending")))

(defn ^{:stratum 2} with-non-controller-rbac-owner []
  (update-in (with-outside-resources) [:kubernetes/pod-inventory "items" 0 "metadata" "ownerReferences"]
             conj (inventory/non-controller-rbac-reference)))

(comment ::raw-inventory-and-independently-named-outside-controller-chain)
