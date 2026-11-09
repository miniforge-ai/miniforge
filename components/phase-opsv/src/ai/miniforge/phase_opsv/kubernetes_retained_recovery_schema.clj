;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-retained-recovery-schema
  (:require [ai.miniforge.kubernetes-target.interface :as target]
            [ai.miniforge.phase-opsv.kubernetes-name-schema :as names]
            [ai.miniforge.phase-opsv.kubernetes-replica-change-schema :as replica]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [ai.miniforge.schema.interface :as schema]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} IdentityMetadata (mu/select-keys resource/Metadata ["name" "uid"]))

(defn- ^{:stratum 0} native-resource [version kind metadata]
  (-> resource/Resource
      (mu/assoc "apiVersion" [:= version])
      (mu/assoc "kind" [:= kind])
      (mu/assoc "metadata" metadata)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Namespace (native-resource "v1" "Namespace" IdentityMetadata))

(def ^{:stratum 1} Deployment
  [:and
   (native-resource "apps/v1" "Deployment"
     (mu/merge IdentityMetadata
       [:map ["name" names/ResourceName] ["namespace" names/NamespaceName]
        ["resourceVersion" schema/NonBlankString]]))
   [:map ["spec" [:map ["replicas" replica/Count]]]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Inventory
  [:map
   [:runtime/target target/Target]
   [:kubernetes/cluster-id schema/NonBlankString]
   [:kubernetes/namespace Namespace]
   [:kubernetes/deployment Deployment]])

(comment ::reporting-metadata-is-not-needed-for-original-identity-and-count)
