;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-snapshot
  "Fingerprint assembly for an admitted, explicitly captured target."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-images :as images]
            [ai.miniforge.phase-opsv.kubernetes-profiles :as profiles]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} observations [snapshot]
  (let [cluster (profiles/resource-profile (:kubernetes/cluster snapshot))
        namespace (profiles/resource-profile (:kubernetes/namespace snapshot))
        label (:kubernetes/node-pool-label snapshot)
        nodes (profiles/node-profiles label (get-in snapshot [:kubernetes/nodes "items"]))
        pods (profiles/resource-profiles (get-in snapshot [:kubernetes/pods "items"]))
        configuration (profiles/resource-profiles (:kubernetes/configuration snapshot))]
    {:kubernetes/cluster cluster
     :kubernetes/namespace namespace
     :kubernetes/nodes nodes
     :kubernetes/pods pods
     :kubernetes/configuration configuration}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} fingerprint [snapshot]
  (let [observed (observations snapshot)
        profiles (concat [(:kubernetes/cluster observed) (:kubernetes/namespace observed)]
                         (:kubernetes/nodes observed) (:kubernetes/pods observed)
                         (:kubernetes/configuration observed))
        failure (first (filter anomaly/anomaly? profiles))
        pods (profiles/ordered (get-in snapshot [:kubernetes/pods "items"]))
        image-observations (vec (mapcat images/pod-images pods))]
    (or failure
        (assoc observed :kubernetes/cluster-id (:kubernetes/cluster-id snapshot)
                        :kubernetes/images image-observations))))

(comment ::stable-captured-environment-fingerprint)
