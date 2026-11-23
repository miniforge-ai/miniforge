;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-metadata
  (:require [ai.miniforge.phase-opsv.kubernetes-reporting-fields :as fields]
            [ai.miniforge.phase-opsv.kubernetes-reporting-policy :as policy]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reference [value]
  (fields/retain-fields policy/reference-fields value))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} references [value]
  (fields/vector-values reference value))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [value]
  (fields/replace-present (fields/retain-fields policy/metadata-fields value) value "ownerReferences" references))
