;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-resource
  (:require [ai.miniforge.phase-opsv.kubernetes-reporting-fields :as fields]
            [ai.miniforge.phase-opsv.kubernetes-reporting-metadata :as metadata]
            [ai.miniforge.phase-opsv.kubernetes-reporting-policy :as policy]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} desired-count [value]
  (if (and (int? value) (<= 0 value Integer/MAX_VALUE)) value (fields/confidential value)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} controller-spec [value]
  (fields/replace-present (fields/retain-fields #{} value) value "replicas" desired-count))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [value]
  (let [base (fields/retain-fields policy/resource-fields value)
        identified (fields/replace-present base value "metadata" metadata/project)
        type-id [(get value "apiVersion") (get value "kind")]]
    (if (contains? policy/controller-types type-id)
      (fields/replace-present identified value "spec" controller-spec) identified)))
