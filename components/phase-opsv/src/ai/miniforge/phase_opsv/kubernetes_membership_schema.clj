;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-membership-schema
  (:require [ai.miniforge.phase-opsv.environment-target-schema :as target]
            [ai.miniforge.phase-opsv.kubernetes-controller-schema :as controller]
            [ai.miniforge.phase-opsv.kubernetes-list-schema :as lists]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [ai.miniforge.schema.interface :as schema]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} PodItem
  (mu/assoc lists/PodItem "metadata" controller/ControllerMetadata))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Observation
  [:map {:closed true}
   [:runtime/target target/Target]
   [:kubernetes/cluster-id schema/NonBlankString]
   [:kubernetes/namespace resource/Namespace]
   [:kubernetes/deployment controller/DeploymentIdentity]
   [:kubernetes/replica-sets [:vector controller/ReplicaSetIdentity]]
   [:kubernetes/pods (mu/assoc lists/PodList "items" [:vector PodItem])]])

(comment ::identity-only-controller-shapes-retain-native-observations)
