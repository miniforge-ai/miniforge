;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-snapshot-schema
  "Captured observations for one explicit target; no acquisition or authority."
  (:require [ai.miniforge.phase-opsv.kubernetes-configuration-schema :as configuration]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [ai.miniforge.phase-opsv.kubernetes-list-schema :as lists]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ConfigurationResource
  [:or configuration/ConfigMap resource/Secret])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Snapshot
  [:map {:closed true}
   [:kubernetes/cluster-id schema/NonBlankString]
   [:kubernetes/cluster resource/Namespace]
   [:kubernetes/namespace resource/Namespace]
   [:kubernetes/node-pool-label schema/NonBlankString]
   [:kubernetes/nodes lists/NodeList]
   [:kubernetes/pods lists/PodList]
   [:kubernetes/configuration [:vector ConfigurationResource]]])

(comment ::explicit-kubernetes-target-snapshot)
