;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-list-schema :as lists]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [ai.miniforge.phase-opsv.kubernetes-volume-schema :as volumes]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory-schema :as inventory]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Input
  [:map {:closed true}
   [:kubernetes/inventory inventory/Observation]
   [:kubernetes/node-pool-label schema/NonBlankString]
   [:kubernetes/nodes lists/CompleteNodeList]])

(def ^{:stratum 0} RuntimePods
  [:vector [:and resource/Pod [:map ["spec" volumes/PodSpec]]]])
