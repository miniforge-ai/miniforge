;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-containers
  (:require [ai.miniforge.phase-opsv.kubernetes-images :as images]
            [ai.miniforge.phase-opsv.kubernetes-reporting-fields :as fields]
            [ai.miniforge.phase-opsv.kubernetes-reporting-policy :as policy]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} container [value]
  (fields/retain-fields policy/container-fields value))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pod-spec [value]
  (reduce (fn [spec [field _status _kind]]
            (fields/update-present spec field (partial fields/vector-values container)))
          (fields/retain-fields policy/pod-fields value) images/container-groups))
