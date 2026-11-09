;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial-admission
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial-observation :as observation]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} readiness! [runtime input]
  (anomaly/let-ok [ready (admission/readiness! runtime input)
                  observed (observation/observe! input (:recovery/plan ready) (:comparison/original ready))]
    (assoc ready :trial/observation observed)))

(comment
  (readiness! {} {}))
