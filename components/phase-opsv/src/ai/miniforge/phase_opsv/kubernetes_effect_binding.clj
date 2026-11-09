;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-binding)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} reference [record]
  (first (get-in record [:effect/proposal :opsv/experiment :opsv/resources])))

(defn ^{:stratum 0} request [record] (get-in record [:effect/proposal :kubernetes/request]))

(defn- ^{:stratum 0} pod-reference [pod]
  (let [metadata (get pod "metadata")
        name (get metadata "name")
        namespace (get metadata "namespace")
        uid (get metadata "uid")]
    {:name name
     :namespace namespace
     :uid uid}))

(defn- ^{:stratum 0} status-reference [status]
  (let [details (get status "details")
        name (get details "name")
        uid (get details "uid")]
    {:name name
     :uid uid}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} same-pod? [record pod]
  (= (select-keys (reference record) [:name :namespace :uid]) (pod-reference pod)))

(defn ^{:stratum 1} same-status? [record status]
  (= (select-keys (reference record) [:name :uid]) (status-reference status)))

(comment ::shared-native-transaction-access-not-admission)
