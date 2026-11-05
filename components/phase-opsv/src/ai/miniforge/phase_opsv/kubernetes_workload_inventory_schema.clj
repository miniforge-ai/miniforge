;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-workload-inventory-schema
  (:require [ai.miniforge.phase-opsv.environment-target-schema :as target]
            [ai.miniforge.phase-opsv.kubernetes-controller-schema :as controller]
            [ai.miniforge.phase-opsv.kubernetes-list-schema :as lists]
            [ai.miniforge.phase-opsv.kubernetes-name-schema :as names]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [ai.miniforge.phase-opsv.kubernetes-workload-identity-schema :as identity]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Target
  [:map {:closed true}
   [:runtime/target target/Target]
   [:kubernetes/deployment-name names/ResourceName]])

(def ^{:stratum 0} PodItem (identity/native-item "v1" "Pod"))

(def ^{:stratum 0} ReplicaSetItem (identity/native-item "apps/v1" "ReplicaSet"))

(defn- ^{:stratum 0} native-list [api-version kind item]
  [:map ["apiVersion" [:= api-version]] ["kind" [:= kind]]
   ["metadata" lists/ListMetadata] ["items" [:vector item]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} PodList (native-list "v1" "PodList" PodItem))

(def ^{:stratum 1} ReplicaSetList (native-list "apps/v1" "ReplicaSetList" ReplicaSetItem))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Observation
  [:map {:closed true}
   [:runtime/target target/Target]
   [:kubernetes/cluster-id schema/NonBlankString]
   [:kubernetes/namespace resource/Namespace]
   [:kubernetes/deployment controller/DeploymentIdentity]
   [:kubernetes/replica-set-inventory ReplicaSetList]
   [:kubernetes/pod-inventory PodList]])

(comment ::complete-namespace-inventory-not-a-proven-member-population)
