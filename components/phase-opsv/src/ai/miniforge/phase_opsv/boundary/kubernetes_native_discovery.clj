;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-native-discovery
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-client :as kubernetes]
            [ai.miniforge.phase-opsv.boundary.kubernetes-environment-fingerprint :as fingerprint]
            [ai.miniforge.phase-opsv.boundary.kubernetes-signals :as signals]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-discovery-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acquired! [configuration]
  (let [settings (:discovery/kubernetes configuration)
        declared (:discovery/declaration configuration)
        bounds (:discovery/replica-bounds configuration)]
    (anomaly/let-ok [captured (kubernetes/observe-verification! settings declared)
                    projected (fingerprint/project captured bounds)
                    drivers (signals/observe! configuration)]
      {:discovery/kubernetes-verification projected
       :discovery/candidate-drivers drivers})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} discover! [configuration]
  (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
  (anomaly/let-ok [_finite (reporting/admit configuration)]
    (if (m/validate schema/NativeConfiguration configuration)
      (acquired! configuration)
      (anomaly/anomaly :invalid-input (msg/ts :discovery/invalid-settings) {}))))

(comment ::original-sensitive-observations-not-a-match-or-authority-verdict)
