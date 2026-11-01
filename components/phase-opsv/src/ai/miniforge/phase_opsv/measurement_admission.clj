;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.measurement-admission
  "Read-only measurement contracts for the Kubernetes/Prometheus OPSV backend."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.measurement :as boundary]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.measurement-schema :as schema]
            [ai.miniforge.phase-opsv.measurement-window :as window]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} decode-window
  "Decode a parsed Prometheus range response against a predeclared bounded grid.
   Require one aggregated float series, without gaps or API warnings. This
   returns observations, not a verification verdict or execution authority."
  [request response]
  (if (and (m/validate schema/WindowRequest request) (window/bounded? request))
    (boundary/decode request response)
    (anomaly/anomaly :invalid-input (msg/ts :measurement/invalid-request) {})))

(comment ::prometheus-measurement-boundary)
