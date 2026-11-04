;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-model
  (:require [ai.miniforge.phase-opsv.kubernetes-reporting-containers :as containers]
            [ai.miniforge.phase-opsv.kubernetes-reporting-fields :as fields]
            [ai.miniforge.phase-opsv.kubernetes-reporting-policy :as policy]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resource? [value]
  (and (string? (get value "kind"))
       (some #(contains? value %) ["metadata" "spec" "data" "binaryData" "stringData" "items"])))

(defn- ^{:stratum 0} template [value]
  (fields/update-present (fields/retain-fields policy/template-fields value) "spec" containers/pod-spec))

(defn- ^{:stratum 0} native-fields [value]
  (-> value
      (fields/update-present "metadata" (partial fields/retain-fields policy/metadata-fields))
      (fields/update-present :kubernetes/pod-spec containers/pod-spec)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} controller-spec [value]
  (fields/update-present (fields/retain-fields policy/controller-fields value) "template" template))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} node [value]
  (let [value (native-fields value)
        native-type [(get value "apiVersion") (get value "kind")]
        transform (case native-type ["v1" "Pod"] containers/pod-spec
                                   ["apps/v1" "Deployment"] controller-spec
                                   ["apps/v1" "ReplicaSet"] controller-spec
                                   fields/confidential)
        allowed (conj policy/resource-fields "spec")]
    (if (resource? value)
      (fields/update-present (fields/retain-fields allowed value) "spec" transform)
      value)))
