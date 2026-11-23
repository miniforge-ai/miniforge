;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-verification-plan
  (:require [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory :as inventory]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} template-pod [controller]
  {"metadata" {"namespace" (resource/namespace-of controller)}
   "spec" (get-in controller ["spec" "template" "spec"])})

(defn ^{:stratum 0} used-resources [captured]
  (inc (count (inventory/resources captured))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} dependency-pods [captured]
  (let [controllers (cons (:kubernetes/deployment captured)
                         (get-in captured [:kubernetes/replica-set-inventory "items"]))]
    (into (get-in captured [:kubernetes/pod-inventory "items"])
          (map template-pod) controllers)))
