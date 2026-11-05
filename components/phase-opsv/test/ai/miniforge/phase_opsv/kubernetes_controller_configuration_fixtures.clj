;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-controller-configuration-fixtures
  (:require [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-outside-fixtures :as outside]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} count-bounds [minimum maximum]
  {:replicas/min-count minimum :replicas/max-count maximum})

(defn ^{:stratum 0} with-desired-counts [input deployment old-set new-set]
  (-> input
      (assoc-in [:kubernetes/inventory :kubernetes/deployment "spec" "replicas"] deployment)
      (assoc-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items" 0 "spec" "replicas"] old-set)
      (assoc-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items" 1 "spec" "replicas"] new-set)))

(defn ^{:stratum 0} with-orderable-metadata [input path]
  (-> input
      (assoc-in (conj path "finalizers") ["example.invalid/hold" "example.invalid/archive"])
      (update-in (conj path "ownerReferences") (fnil conj []) (inventory/non-controller-rbac-reference))))

(defn ^{:stratum 0} reverse-metadata [input path]
  (-> input
      (update-in (conj path "finalizers") (comp vec reverse))
      (update-in (conj path "ownerReferences") (comp vec reverse))))

(defn ^{:stratum 0} outside-capture [input]
  (assoc (outside/input) :kubernetes/inventory (:kubernetes/inventory input)))

(defn- ^{:stratum 0} controller [replicas value]
  (let [labels {"app" "catalog"}
        spec (dissoc (get (kube/pod) "spec") "nodeName")]
    (assoc value "spec" {"replicas" replicas
                         "selector" {"matchLabels" labels}
                         "template" {"metadata" {"labels" labels "annotations" {"replicas" "configuration-value"}}
                                      "spec" spec}})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} zero-population [input]
  (-> input
      (assoc :comparison/bounds (count-bounds 0 0))
      (assoc-in [:kubernetes/inventory :kubernetes/deployment "spec" "replicas"] 0)
      (assoc-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items"] [])
      (assoc-in [:kubernetes/inventory :kubernetes/pod-inventory "items"] [])))

(defn- ^{:stratum 1} configured-inventory [captured]
  (-> captured
      (update :kubernetes/deployment (partial controller 2))
      (update-in [:kubernetes/replica-set-inventory "items" 0] (partial controller 1))
      (update-in [:kubernetes/replica-set-inventory "items" 1] (partial controller 1))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} input []
  (let [captured (configured-inventory (:kubernetes/inventory (outside/input)))
        bounds (count-bounds 1 3)]
    {:kubernetes/inventory captured
     :comparison/bounds bounds}))
