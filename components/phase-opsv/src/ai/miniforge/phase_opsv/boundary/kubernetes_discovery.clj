;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-discovery
  "Compose bounded read-only acquisition; each transport admits its own request."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-client :as kubernetes]
            [ai.miniforge.phase-opsv.boundary.prometheus-client :as prometheus]
            [ai.miniforge.phase-opsv.kubernetes-discovery-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-signal-model :as model]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} observe-driver! [configuration expected kind]
  (let [settings (:discovery/prometheus configuration)
        request (get-in configuration [:discovery/queries kind])]
    (anomaly/let-ok [observation (prometheus/observe-window! settings request)]
      (model/driver kind expected observation))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} acquired! [configuration]
  (let [expected (model/expected-labels configuration)
        settings (:discovery/kubernetes configuration)]
    (anomaly/let-ok [fingerprint (kubernetes/observe-fingerprint! settings)
                     cpu (observe-driver! configuration expected :cpu)
                     backlog (observe-driver! configuration expected :backlog)]
      {:discovery/kubernetes-fingerprint fingerprint
       :discovery/candidate-drivers [cpu backlog]})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} discover! [configuration]
  (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
  (if (m/validate schema/Configuration configuration)
    (acquired! configuration)
    (anomaly/anomaly :invalid-input (msg/ts :discovery/invalid-settings) {})))

(comment ::bounded-read-only-signal-discovery)
