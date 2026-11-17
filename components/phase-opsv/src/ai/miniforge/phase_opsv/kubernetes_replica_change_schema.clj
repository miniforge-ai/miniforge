;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-change-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-controller-schema :as controller]
            [ai.miniforge.phase-opsv.kubernetes-name-schema :as names]
            [ai.miniforge.phase-opsv.kubernetes-verification-schema :as verification]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Count [:int {:min 0 :max Integer/MAX_VALUE}])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Deployment
  [:and controller/DeploymentIdentity
   [:map ["metadata" [:map ["name" names/ResourceName] ["namespace" names/NamespaceName]
                      ["resourceVersion" schema/NonBlankString]]]
    ["spec" [:map ["replicas" Count]]]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Capture
  [:and verification/Observation
   [:map [:kubernetes/inventory [:map [:kubernetes/deployment Deployment]]]]])

(comment ::native-defaulted-count-and-server-revision-are-required-not-guessed)
