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
  (let [replica-sets (into {} (map (juxt resource/uid desired-count)) (rest controllers))]
    {:controller/deployment-replicas (desired-count (first controllers))
     :controller/replica-set-total (reduce + 0 (vals replica-sets))
     :controller/replica-set-replicas replica-sets}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [input membership controllers]
  (let [observed-counts (counts controllers)]
    (when (in-bounds? (:comparison/bounds input) observed-counts)
      (let [projected {:kubernetes/projection :kubernetes/controller-desired-configuration-v1
                       :runtime/target (:runtime/target membership)
                       :kubernetes/controllers (mapv configuration (profiles/ordered controllers))}]
        (anomaly/let-ok [digest (artifact/content-digest projected)]
          {:kubernetes/source input
           :kubernetes/membership membership
           :kubernetes/controller-counts observed-counts
           :kubernetes/controller-configuration projected
           :kubernetes/controller-configuration-hash digest})))))
