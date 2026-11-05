;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-discovery
  "Compose bounded read-only acquisition; each transport admits its own request."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-client :as kubernetes]
            [ai.miniforge.phase-opsv.boundary.kubernetes-signals :as signals]
            [ai.miniforge.phase-opsv.kubernetes-discovery-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acquired! [configuration]
  (let [settings (:discovery/kubernetes configuration)]
    (anomaly/let-ok [fingerprint (kubernetes/observe-fingerprint! settings)
                    drivers (signals/observe! configuration)]
      {:discovery/kubernetes-fingerprint fingerprint
       :discovery/candidate-drivers drivers})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} discover! [configuration]
  (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
  (if (m/validate schema/Configuration configuration)
    (acquired! configuration)
    (anomaly/anomaly :invalid-input (msg/ts :discovery/invalid-settings) {})))

(comment ::bounded-read-only-signal-discovery)
