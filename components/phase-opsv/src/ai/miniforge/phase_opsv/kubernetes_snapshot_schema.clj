;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-snapshot-schema
  "Captured observations for one explicit target; no acquisition or authority."
  (:require [ai.miniforge.phase-opsv.kubernetes-configuration-schema :as configuration]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ConfigurationResource
  [:or configuration/ConfigMap resource/Secret resource/Namespace resource/Node resource/Pod])

(def ^{:stratum 0} NodeList
  [:map ["apiVersion" [:= "v1"]] ["kind" [:= "NodeList"]]
   ["items" [:vector {:min 1} resource/Node]]])

(def ^{:stratum 0} PodList
  [:map ["apiVersion" [:= "v1"]] ["kind" [:= "PodList"]]
   ["items" [:vector {:min 1} resource/Pod]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Snapshot
  [:map {:closed true}
   [:kubernetes/cluster-id schema/NonBlankString]
   [:kubernetes/cluster resource/Namespace]
   [:kubernetes/namespace resource/Namespace]
   [:kubernetes/node-pool-label schema/NonBlankString]
   [:kubernetes/nodes NodeList]
   [:kubernetes/pods PodList]
   [:kubernetes/configuration [:vector ConfigurationResource]]])

(comment ::explicit-kubernetes-target-snapshot)
