;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-controller-configuration
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.kubernetes-invariant-resource :as invariant]
            [ai.miniforge.phase-opsv.kubernetes-profiles :as profiles]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} desired-count [value] (get-in value ["spec" "replicas"]))

(defn- ^{:stratum 0} configuration [controller]
  (invariant/profile (update controller "spec" dissoc "replicas")))

(defn- ^{:stratum 0} in-bounds? [bounds counts]
  (let [lower (:replicas/min-count bounds)
        upper (:replicas/max-count bounds)]
    (and (<= lower (:controller/deployment-replicas counts) upper)
         (<= lower (:controller/replica-set-total counts) upper))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} counts [controllers]
  (let [replica-sets (into {} (map (juxt resource/uid desired-count)) (rest controllers))
        deployment-count (desired-count (first controllers))
        replica-set-total (reduce + 0 (vals replica-sets))]
    {:controller/deployment-replicas deployment-count
     :controller/replica-set-total replica-set-total
     :controller/replica-set-replicas replica-sets}))

(defn- ^{:stratum 1} projection [membership controllers]
  (let [values (mapv configuration (profiles/ordered controllers))]
    {:kubernetes/projection :kubernetes/controller-desired-configuration-v1
     :runtime/target (:runtime/target membership)
     :kubernetes/controllers values}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [input membership controllers]
  (let [observed-counts (counts controllers)]
    (when (in-bounds? (:comparison/bounds input) observed-counts)
      (anomaly/let-ok [projected (projection membership controllers)
                      digest (artifact/content-digest projected)]
        {:kubernetes/source input
         :kubernetes/membership membership
         :kubernetes/controller-counts observed-counts
         :kubernetes/controller-configuration projected
         :kubernetes/controller-configuration-hash digest}))))
