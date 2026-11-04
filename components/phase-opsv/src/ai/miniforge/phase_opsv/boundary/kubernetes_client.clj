;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-client
  "Explicit read-only Kubernetes acquisition; no experiment or mutation authority."
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-acquisition :as acquisition]
            [ai.miniforge.phase-opsv.boundary.kubernetes-capture :as capture]
            [ai.miniforge.phase-opsv.boundary.kubernetes-inventory :as inventory]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.kubernetes-settings-schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fingerprint-settings [overrides]
  (settings/load-settings overrides schema/Settings []))

(defn- ^{:stratum 0} namespace-settings [overrides]
  (settings/load-settings overrides schema/NamespaceInventorySettings
                          [:kubernetes/pod-selector :kubernetes/node-pool-label]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe-fingerprint! [overrides]
  (acquisition/observe! fingerprint-settings overrides capture/capture!))

(defn ^{:stratum 1} observe-inventory! [overrides declaration]
  (acquisition/observe! namespace-settings overrides (partial inventory/capture! declaration)))

(comment ::bounded-kubernetes-fingerprint-ingress)
