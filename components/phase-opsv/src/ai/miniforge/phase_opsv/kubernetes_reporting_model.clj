;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-model
  (:require [ai.miniforge.phase-opsv.kubernetes-reporting-containers :as containers]
            [ai.miniforge.phase-opsv.kubernetes-reporting-fields :as fields]
            [ai.miniforge.phase-opsv.kubernetes-reporting-policy :as policy]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resource? [value]
  (string? (get value "kind")))

(defn- ^{:stratum 0} reference? [value]
  (and (string? (get value "name")) (string? (get value "uid")) (not (contains? value "metadata"))))

(defn- ^{:stratum 0} template [value]
  (fields/update-present (fields/retain-fields policy/template-fields value) "spec" containers/pod-spec))

(defn- ^{:stratum 0} native-fields [value]
  (-> value
      (fields/update-present "metadata" (partial fields/retain-fields policy/metadata-fields))
      (fields/update-present :kubernetes/pod-spec containers/pod-spec)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} native-type [value]
  (when-not (reference? value) [(get value "apiVersion") (get value "kind")]))

(defn- ^{:stratum 1} public-fields [value]
  (conj (if (reference? value) policy/reference-fields policy/resource-fields) "spec"))

(defn- ^{:stratum 1} controller-spec [value]
  (fields/update-present (fields/retain-fields policy/controller-fields value) "template" template))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} node [value]
  (let [value (native-fields value)
        type-id (native-type value)
        transform (case type-id ["v1" "Pod"] containers/pod-spec
                                   ["apps/v1" "Deployment"] controller-spec
                                   ["apps/v1" "ReplicaSet"] controller-spec
                                   fields/confidential)
        allowed (public-fields value)]
    (if (resource? value)
      (fields/update-present (fields/retain-fields allowed value) "spec" transform)
      value)))
