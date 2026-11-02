;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-discovery-schema
  (:require [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} LabelNames
  [:and
   [:map {:closed true}
    [:cluster schema/NonBlankString]
    [:namespace schema/NonBlankString]
    [:service schema/NonBlankString]]
   [:fn #(apply distinct? (vals %))]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Configuration
  [:and
   [:map {:closed true}
    [:discovery/service schema/NonBlankString]
    [:discovery/kubernetes :map]
    [:discovery/prometheus :map]
    [:discovery/metric-labels LabelNames]
    [:discovery/queries [:map {:closed true} [:cpu :map] [:backlog :map]]]]
   [:fn #(not= (get-in % [:discovery/queries :cpu :prometheus/query])
               (get-in % [:discovery/queries :backlog :prometheus/query]))]])

(comment ::explicit-read-only-discovery-configuration)
