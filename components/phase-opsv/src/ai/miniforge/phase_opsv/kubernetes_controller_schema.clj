;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-controller-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} OwnerReference
  [:map
   ["apiVersion" schema/NonBlankString] ["kind" schema/NonBlankString]
   ["name" schema/NonBlankString] ["uid" schema/NonBlankString]
   ["controller" {:optional true} :boolean]
   ["blockOwnerDeletion" {:optional true} :boolean]])

(def ^{:stratum 0} DeploymentIdentity
  [:and resource/Resource
   [:map ["apiVersion" [:= "apps/v1"]] ["kind" [:= "Deployment"]]
    ["metadata" resource/NamespacedMetadata]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ControllerMetadata
  [:and resource/NamespacedMetadata
   [:map ["ownerReferences" [:vector {:min 1} OwnerReference]]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} ReplicaSetIdentity
  [:and resource/Resource
   [:map ["apiVersion" [:= "apps/v1"]] ["kind" [:= "ReplicaSet"]]
    ["metadata" ControllerMetadata]]])

(comment ::controller-identity-is-not-a-workload-configuration-schema)
