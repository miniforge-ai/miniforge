;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-test-support
  "Captured-shape read fixtures; no Kubernetes calls."
  (:require [ai.miniforge.phase-opsv.kubernetes-container-test-support :as containers]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pool-label "example.invalid/node-pool")

(def ^{:stratum 0} image-id
  "docker-pullable://registry.invalid/service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn ^{:stratum 0} metadata [name namespace uid]
  (cond-> {"name" name
           "uid" uid
           "resourceVersion" "42"
           "creationTimestamp" "2026-11-01T00:00:00Z"}
    namespace (assoc "namespace" namespace)))

(defn ^{:stratum 0} kube-resource [kind metadata fields]
  (merge {"apiVersion" "v1" "kind" kind "metadata" metadata} fields))

(defn ^{:stratum 0} resource-list [kind items]
  {"apiVersion" "v1" "kind" kind "items" items})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} config-map []
  (let [meta (assoc (metadata "settings" "staging" "config-uid") "labels" {"app" "service"})]
    (kube-resource "ConfigMap" meta {"data" {"workers" "4"}})))

(defn ^{:stratum 1} secret []
  (let [meta (assoc (metadata "credentials" "staging" "secret-uid")
                    "annotations" {"example.invalid/credential" "private-value"})]
    (kube-resource "Secret" meta {"data" {"token" "cHJpdmF0ZS12YWx1ZQ=="}})))

(defn ^{:stratum 1} node []
  (let [meta (assoc (metadata "worker-a" nil "node-uid") "labels" {pool-label "pool-a"})
        runtime {"architecture" "amd64" "operatingSystem" "linux"
                 "kernelVersion" "6.8.0" "containerRuntimeVersion" "containerd://2.0.0"
                 "kubeletVersion" "v1.33.0"}]
    (kube-resource "Node" meta {"status" {"nodeInfo" runtime}})))

(defn ^{:stratum 1} pod []
  (let [meta (metadata "service-a" "staging" "pod-uid")
        spec {"nodeName" "worker-a"
               "containers" [(containers/container "service")]}
        status {"containerStatuses" [(containers/status "service" image-id)]}]
    (kube-resource "Pod" meta {"spec" spec "status" status})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} snapshot []
  (let [cluster (kube-resource "Namespace" (metadata "kube-system" nil "cluster-uid") {})
        namespace (kube-resource "Namespace" (metadata "staging" nil "namespace-uid") {})
        nodes (resource-list "NodeList" [(node)])
        pods (resource-list "PodList" [(pod)])
        configuration [(config-map) (secret)]]
    {:kubernetes/cluster-id "staging-cluster"
     :kubernetes/cluster cluster
     :kubernetes/namespace namespace
     :kubernetes/node-pool-label pool-label
     :kubernetes/nodes nodes
     :kubernetes/pods pods
     :kubernetes/configuration configuration}))

(comment ::captured-kubernetes-read-fixtures)
