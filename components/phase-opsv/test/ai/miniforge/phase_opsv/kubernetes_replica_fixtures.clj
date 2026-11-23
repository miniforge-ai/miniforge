;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-fixtures
  (:require [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-partition-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]
            [ai.miniforge.phase-opsv.kubernetes-volume-scenarios :as volumes]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-volume [name pod]
  (assoc pod "spec" (volumes/pod-spec name)))

(defn- ^{:stratum 0} expectation [result]
  (let [first-observation (first (:comparison/replica-observations result))]
    (assoc (select-keys first-observation [:replica/target :replica/template-hash :replica/image-digests])
           :replica/node-pools [(:replica/node-pool first-observation)]
           :replicas/min-count 1 :replicas/max-count 3)))

(defn- ^{:stratum 0} fingerprint [result]
  (let [observed (first (:comparison/replica-observations result))]
    {:cluster (get-in observed [:replica/target :cluster])
     :node-pools [(:replica/node-pool observed)]
     :image-digests (:replica/image-digests observed)
     :config-hash "separately-checked-global-configuration"}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} replace-pod [owned pod]
  (if (contains? owned (get-in pod ["metadata" "uid"]))
    (-> (with-volume "kube-api-access-vwxz2" pod)
        (update-in ["metadata" "uid"] #(str "replacement-" %))
        (update-in ["metadata" "name"] #(str "replacement-" %))
        (assoc-in ["spec" "nodeName"] "worker-b"))
    pod))

(defn ^{:stratum 1} input []
  (let [raw (inventory/with-outside-resources)
        nodes [(kube/node) (-> (kube/node)
                              (assoc-in ["metadata" "name"] "worker-b")
                              (assoc-in ["metadata" "uid"] "node-b-uid"))]]
    {:kubernetes/inventory (update-in raw [:kubernetes/pod-inventory "items"]
                                    (partial mapv (partial with-volume "kube-api-access-bcdfg")))
     :kubernetes/node-pool-label kube/pool-label
     :kubernetes/nodes (assoc (kube/resource-list "NodeList" nodes) "metadata"
                              (get-in raw [:kubernetes/pod-inventory "metadata"]))}))

(defn- ^{:stratum 1} plan [baseline]
  (let [expected (expectation baseline)
        binding (assoc (environment/verification-binding) :runtime/target (:replica/target expected))]
    {:comparison/binding binding
     :comparison/baseline (fingerprint baseline)
     :comparison/changes {}
     :comparison/replica-contract {:candidate expected :rollback expected}}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} replacement [input]
  (let [partition (phase/partition-kubernetes-deployment-inventory (:kubernetes/inventory input))
        owned (set (map #(get-in % ["metadata" "uid"])
                        (get-in partition [:kubernetes/membership :kubernetes/pods "items"])))]
    (update-in input [:kubernetes/inventory :kubernetes/pod-inventory "items"]
               (partial mapv (partial replace-pod owned)))))

(defn ^{:stratum 2} request [baseline observed]
  (let [declared (plan baseline)
        pin (phase/pin-verification-environment declared)]
    (assoc (environment/request pin :candidate (:comparison/baseline declared))
           :comparison/replica-observations (:comparison/replica-observations observed))))
