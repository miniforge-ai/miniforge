;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-outside-inventory
  (:require [ai.miniforge.phase-opsv.kubernetes-list-model :as lists]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory :as inventory]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} owned-uids [membership]
  (into #{(resource/uid (:kubernetes/deployment membership))}
        (map resource/uid)
        (concat (:kubernetes/replica-sets membership) (get-in membership [:kubernetes/pods "items"]))))

(defn ^{:stratum 0} unique-identities? [resources]
  (let [uids (map resource/uid resources)
        logical (map (comp pop resource/identity-key) resources)]
    (and (= (count uids) (count (distinct uids)))
         (= (count logical) (count (distinct logical))))))

(defn ^{:stratum 0} resources [captured]
  (let [raw (-> (:kubernetes/inventory captured)
                (update :kubernetes/replica-set-inventory (partial lists/typed-list "ReplicaSet"))
                (update :kubernetes/pod-inventory (partial lists/typed-list "Pod")))
        nodes (lists/typed-list "Node" (:kubernetes/nodes captured))
        cluster (:kubernetes/cluster captured)
        cluster-root (when-not (= cluster (:kubernetes/namespace raw)) [cluster])]
    (vec (concat cluster-root (inventory/resources raw)
                 (get nodes "items") (:kubernetes/configuration captured)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} outside [resources membership]
  (let [owned (owned-uids membership)]
    (remove #(contains? owned (resource/uid %)) resources)))
