;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-inventory-partition
  (:require [ai.miniforge.phase-opsv.kubernetes-controller-reference :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-inventory-links :as links]
            [ai.miniforge.phase-opsv.kubernetes-list-model :as lists]
            [ai.miniforge.phase-opsv.kubernetes-membership :as membership]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.kubernetes-resource-selection :as selection]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory :as inventory]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} complete? [raw]
  (let [collections [(:kubernetes/replica-set-inventory raw) (:kubernetes/pod-inventory raw)]
        namespace (get-in raw [:runtime/target :namespace])]
    (and (every? selection/complete? collections)
         (every? #(= namespace (resource/namespace-of %)) (mapcat #(get % "items") collections))
         (inventory/unique-identities? raw))))

(defn- ^{:stratum 0} members [raw replica-sets pod-list]
  (let [owned-sets (filterv (partial ownership/owns? (:kubernetes/deployment raw)) replica-sets)
        owner-uids (set (map resource/uid owned-sets))
        owned-pods (filterv #(contains? owner-uids (get (ownership/controller %) "uid")) (get pod-list "items"))]
    (assoc (select-keys raw [:runtime/target :kubernetes/cluster-id :kubernetes/namespace :kubernetes/deployment])
           :kubernetes/replica-sets owned-sets
           :kubernetes/pods (assoc pod-list "items" owned-pods))))

(defn- ^{:stratum 0} admitted-view [raw selected]
  (when (membership/consistent? selected)
    {:kubernetes/inventory raw :kubernetes/membership selected}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} project [raw]
  (let [replica-sets (get (lists/typed-list "ReplicaSet" (:kubernetes/replica-set-inventory raw)) "items")
        pod-list (lists/typed-list "Pod" (:kubernetes/pod-inventory raw))]
    (when (and (complete? raw) (links/consistent? raw replica-sets (get pod-list "items")))
      (admitted-view raw (members raw replica-sets pod-list)))))

(comment ::raw-observations-retained-alongside-the-proven-semantic-member-view)
