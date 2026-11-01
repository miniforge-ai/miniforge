;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-snapshot
  "Fingerprint assembly for an admitted, explicitly captured target."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.kubernetes-images :as images]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} sorted-resources [resources]
  (sort-by resource/identity-key resources))

(defn- ^{:stratum 0} profile [resource content]
  (let [id (resource/identity-key resource)
        digest (artifact/content-digest content)]
    (if (anomaly/anomaly? digest)
      digest
      {:resource/id id
       :resource/hash digest})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resource-profile [resource]
  (profile resource (resource/configuration resource)))

(defn- ^{:stratum 1} node-profile [label node]
  (let [desired (resource/configuration node)
        runtime (get-in node ["status" "nodeInfo"])
        content {:node/desired desired
                  :node/runtime runtime}
        observed (profile node content)
        pool (get-in node ["metadata" "labels" label])]
    (cond-> observed
      (not (anomaly/anomaly? observed)) (assoc :node/pool pool))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} fingerprint [snapshot]
  (let [cluster-id (:kubernetes/cluster-id snapshot)
        cluster (resource-profile (:kubernetes/cluster snapshot))
        namespace (resource-profile (:kubernetes/namespace snapshot))
        label (:kubernetes/node-pool-label snapshot)
        nodes (mapv (partial node-profile label) (sorted-resources (get-in snapshot [:kubernetes/nodes "items"])))
        pods (sorted-resources (get-in snapshot [:kubernetes/pods "items"]))
        pod-profiles (mapv resource-profile pods)
        image-observations (vec (mapcat images/pod-images pods))
        configuration (mapv resource-profile (sorted-resources (:kubernetes/configuration snapshot)))
        failure (first (filter anomaly/anomaly? (concat [cluster namespace] nodes pod-profiles configuration)))]
    (or failure
        {:kubernetes/cluster-id cluster-id
         :kubernetes/cluster cluster
         :kubernetes/namespace namespace
         :kubernetes/nodes nodes
         :kubernetes/pods pod-profiles
         :kubernetes/images image-observations
         :kubernetes/configuration configuration})))

(comment ::stable-captured-environment-fingerprint)
