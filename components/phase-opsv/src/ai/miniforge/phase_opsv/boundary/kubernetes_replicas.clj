;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-replicas
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-inventory-partition :as partition]
            [ai.miniforge.phase-opsv.kubernetes-replica-observations :as replicas]
            [ai.miniforge.phase-opsv.kubernetes-replica-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-replicas) {}))

(defn- ^{:stratum 0} conflict []
  (anomaly/anomaly :conflict (msg/ts :kubernetes/uncorrelated-replicas) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admitted-input [input]
  (if (m/validate schema/Input input) input (invalid)))

(defn- ^{:stratum 1} membership [input]
  (or (:kubernetes/membership (partition/project (:kubernetes/inventory input))) (conflict)))

(defn- ^{:stratum 1} admitted-runtime [membership]
  (if (m/validate schema/RuntimePods (get-in membership [:kubernetes/pods "items"])) membership (invalid)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [input]
  (anomaly/let-ok [input (admitted-input input)
                  membership (membership input)
                  membership (admitted-runtime membership)]
    (or (replicas/project input membership) (conflict))))
