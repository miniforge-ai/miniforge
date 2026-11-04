;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-client
  "Explicit read-only Kubernetes acquisition; no experiment or mutation authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-acquisition :as acquisition]
            [ai.miniforge.phase-opsv.boundary.kubernetes-capture :as capture]
            [ai.miniforge.phase-opsv.boundary.kubernetes-inventory :as inventory]
            [ai.miniforge.phase-opsv.boundary.kubernetes-verification-capture :as verification]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.kubernetes-inventory-observation :as observation]
            [ai.miniforge.phase-opsv.kubernetes-settings-schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fingerprint-settings [overrides]
  (settings/load-settings overrides schema/Settings []))

(defn- ^{:stratum 0} namespace-settings [overrides]
  (settings/load-settings overrides schema/NamespaceInventorySettings
                          [:kubernetes/pod-selector :kubernetes/node-pool-label]))

(defn- ^{:stratum 0} verification-settings [overrides]
  (settings/load-settings overrides schema/VerificationSettings [:kubernetes/pod-selector]))

(defn- ^{:stratum 0} inventory-observation! [declaration settings until]
  (anomaly/let-ok [captured (inventory/capture-with-roots! declaration settings until)]
    (observation/latest captured)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe-fingerprint! [overrides]
  (acquisition/observe! fingerprint-settings overrides capture/capture!))

(defn ^{:stratum 1} observe-inventory! [overrides declaration]
  (acquisition/observe! namespace-settings overrides (partial inventory-observation! declaration)))

(defn ^{:stratum 1} observe-verification! [overrides declaration]
  (acquisition/observe! verification-settings overrides (partial verification/capture! declaration)))

(comment ::bounded-kubernetes-fingerprint-ingress)
