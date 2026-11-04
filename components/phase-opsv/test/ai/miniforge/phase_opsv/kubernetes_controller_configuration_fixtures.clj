;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-controller-configuration-fixtures
  (:require [ai.miniforge.phase-opsv.kubernetes-outside-fixtures :as outside]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} controller [replicas value]
  (let [labels {"app" "catalog"}
        spec (dissoc (get (kube/pod) "spec") "nodeName")]
    (assoc value "spec" {"replicas" replicas
                         "selector" {"matchLabels" labels}
                         "template" {"metadata" {"labels" labels "annotations" {"replicas" "configuration-value"}}
                                      "spec" spec}})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} configured-inventory [captured]
  (-> captured
      (update :kubernetes/deployment (partial controller 2))
      (update-in [:kubernetes/replica-set-inventory "items" 0] (partial controller 1))
      (update-in [:kubernetes/replica-set-inventory "items" 1] (partial controller 1))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} input []
  (let [captured (configured-inventory (:kubernetes/inventory (outside/input)))]
    {:kubernetes/inventory captured
     :comparison/bounds {:replicas/min-count 1 :replicas/max-count 3}}))
