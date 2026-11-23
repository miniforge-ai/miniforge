;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-metadata-projection)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private ownership-and-lifecycle
  "Native ownership and deletion metadata kept outside desired labels/annotations."
  ["ownerReferences" "finalizers" "deletionTimestamp" "deletionGracePeriodSeconds"])

(defn- ^{:stratum 0} order-present [metadata field order]
  (if (contains? metadata field) (update metadata field #(vec (order %))) metadata))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} project [metadata]
  (-> (select-keys metadata ownership-and-lifecycle)
      (order-present "finalizers" sort)
      (order-present "ownerReferences" #(sort-by (fn [reference] (get reference "uid")) %))))
