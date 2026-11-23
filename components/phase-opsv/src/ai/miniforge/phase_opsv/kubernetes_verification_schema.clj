;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-verification-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-dependency-schema :as dependencies]
            [ai.miniforge.phase-opsv.kubernetes-configuration-schema :as configuration]
            [ai.miniforge.phase-opsv.kubernetes-list-schema :as lists]
            [ai.miniforge.phase-opsv.kubernetes-name-schema :as names]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory-schema :as inventory]
            [ai.miniforge.schema.interface :as schema]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} DependencySpec (mu/dissoc dependencies/PodDependencies "nodeName"))

(def ^{:stratum 0} Observation
  [:map {:closed true}
   [:kubernetes/inventory inventory/Observation]
   [:kubernetes/cluster resource/Namespace]
   [:kubernetes/nodes lists/CompleteNodeList]
   [:kubernetes/node-pool-label schema/NonBlankString]
   [:kubernetes/configuration [:vector [:or configuration/ConfigMap resource/Secret]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} DependencyPods
  [:vector [:map ["metadata" [:map ["namespace" names/NamespaceName]]]
            ["spec" DependencySpec]]])
