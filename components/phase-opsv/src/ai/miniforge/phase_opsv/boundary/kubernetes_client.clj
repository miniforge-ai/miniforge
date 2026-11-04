;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-client
  "Explicit read-only Kubernetes acquisition; no experiment or mutation authority."
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-acquisition :as acquisition]
            [ai.miniforge.phase-opsv.boundary.kubernetes-capture :as capture]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} observe-fingerprint! [overrides]
  (acquisition/observe! overrides capture/capture!))

(comment ::bounded-kubernetes-fingerprint-ingress)
