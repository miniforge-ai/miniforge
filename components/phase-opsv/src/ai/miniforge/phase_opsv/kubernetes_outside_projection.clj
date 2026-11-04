;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-outside-projection
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.kubernetes-metadata-projection :as metadata]
            [ai.miniforge.phase-opsv.kubernetes-outside-inventory :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-profiles :as profiles]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} profile [value]
  (let [ownership (metadata/project (get value "metadata"))]
    {:resource/id (resource/identity-key value)
     :resource/configuration (resource/configuration value)
     :resource/ownership-and-lifecycle ownership
     :resource/runtime (resource/observed-runtime value)}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} project [input membership resources]
  (let [outside (profiles/ordered (inventory/outside resources membership))
        resource-profiles (mapv profile outside)
        projected {:kubernetes/projection :kubernetes/outside-workload-invariants-v1
                   :kubernetes/cluster-id (get-in input [:kubernetes/inventory :kubernetes/cluster-id])
                   :kubernetes/node-pool-label (:kubernetes/node-pool-label input)
                   :kubernetes/resources resource-profiles}]
    (anomaly/let-ok [digest (artifact/content-digest projected)]
      {:kubernetes/source input
       :kubernetes/membership membership
       :kubernetes/outside-projection projected
       :kubernetes/outside-hash digest})))
