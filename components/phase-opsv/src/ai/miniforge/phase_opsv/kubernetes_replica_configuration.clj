;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-configuration
  (:require [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.kubernetes-volume-bindings :as bindings]
            [ai.miniforge.phase-opsv.kubernetes-volume-projection :as volumes]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} kind :kubernetes/deployment-replica-configuration-v1)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} project [pool-label pod]
  ;; Placement is correlated independently against captured Nodes and compared by pool.
  (let [spec (dissoc (get pod "spec") "nodeName")]
    (when-let [plan (bindings/plan spec)]
      {:kubernetes/projection kind
       :kubernetes/node-pool-label pool-label
       :kubernetes/resource (dissoc (resource/configuration pod) "spec")
       :kubernetes/pod-spec-projection (volumes/project plan spec)})))
