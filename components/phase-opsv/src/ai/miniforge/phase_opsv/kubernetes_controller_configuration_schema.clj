;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-controller-configuration-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-workload-inventory-schema :as inventory]
            [ai.miniforge.phase-opsv.replica-comparison-schema :as replicas]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Input
  [:map {:closed true}
   [:kubernetes/inventory inventory/Observation]
   [:comparison/bounds replicas/CountBounds]])

(def ^{:stratum 0} NativeScales
  [:vector {:min 1} [:map ["spec" [:map ["replicas" [:int {:min 0 :max Integer/MAX_VALUE}]]]]]])
