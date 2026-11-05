;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-membership-fixtures
  (:require [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} target []
  {:cluster "staging-cluster" :namespace "staging"
   :namespace-uid "namespace-uid" :workload-uid "deployment-uid"})

(defn ^{:stratum 0} owner-reference [owner]
  (merge (select-keys owner ["apiVersion" "kind"])
         (select-keys (get owner "metadata") ["name" "uid"])
         {"controller" true}))

(defn ^{:stratum 0} deployment []
  (assoc (kube/kube-resource "Deployment" (kube/metadata "catalog" "staging" "deployment-uid") {})
         "apiVersion" "apps/v1"))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} replica-set [name uid]
  (-> (kube/kube-resource "ReplicaSet" (kube/metadata name "staging" uid) {})
      (assoc "apiVersion" "apps/v1")
      (assoc-in ["metadata" "ownerReferences"] [(owner-reference (deployment))])))

(defn ^{:stratum 1} pod [name uid parent]
  (assoc (kube/pod) "metadata"
         (assoc (kube/metadata name "staging" uid) "ownerReferences" [(owner-reference parent)])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observation []
  (let [old (replica-set "catalog-old" "old-replica-set-uid")
        replacement (replica-set "catalog-new" "new-replica-set-uid")]
    (assoc (select-keys (kube/snapshot) [:kubernetes/cluster-id :kubernetes/namespace])
           :runtime/target (target)
           :kubernetes/deployment (deployment)
           :kubernetes/replica-sets [old replacement]
           :kubernetes/pods (kube/resource-list "PodList"
                              [(pod "catalog-old-a" "old-pod-uid" old)
                               (pod "catalog-new-a" "new-pod-uid" replacement)]))))

(comment ::independently-declared-target-and-captured-controller-objects)
