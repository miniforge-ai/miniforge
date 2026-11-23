;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-discovery-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-workload-inventory-schema :as inventory]
            [ai.miniforge.phase-opsv.replica-comparison-schema :as replicas]
            [ai.miniforge.schema.interface :as schema]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} LabelNames
  [:and
   [:map {:closed true}
    [:cluster schema/NonBlankString]
    [:namespace schema/NonBlankString]
    [:service schema/NonBlankString]]
   [:fn #(apply distinct? (vals %))]])

(defn- ^{:stratum 0} distinct-queries? [configuration]
  (not= (get-in configuration [:discovery/queries :cpu :prometheus/query])
        (get-in configuration [:discovery/queries :backlog :prometheus/query])))

(def ^{:stratum 0} NativeFields
  [:map {:closed true}
   [:discovery/declaration inventory/Target]
   [:discovery/replica-bounds replicas/CountBounds]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ConfigurationFields
  [:map {:closed true}
   [:discovery/service schema/NonBlankString]
   [:discovery/kubernetes :map]
   [:discovery/prometheus :map]
   [:discovery/metric-labels LabelNames]
   [:discovery/queries [:map {:closed true} [:cpu :map] [:backlog :map]]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Configuration
  [:and ConfigurationFields [:fn distinct-queries?]])

(def ^{:stratum 2} NativeConfiguration
  [:and (mu/merge ConfigurationFields NativeFields) [:fn distinct-queries?]])

(comment ::explicit-read-only-discovery-configuration)
