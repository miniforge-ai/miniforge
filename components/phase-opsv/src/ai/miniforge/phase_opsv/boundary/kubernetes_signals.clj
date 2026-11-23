;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-signals
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.prometheus-client :as prometheus]
            [ai.miniforge.phase-opsv.kubernetes-signal-model :as model]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} observe-driver! [configuration expected kind]
  (let [settings (:discovery/prometheus configuration)
        request (get-in configuration [:discovery/queries kind])]
    (anomaly/let-ok [observation (prometheus/observe-window! settings request)]
      (model/driver kind expected observation))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe! [configuration]
  (let [expected (model/expected-labels configuration)]
    (anomaly/let-ok [cpu (observe-driver! configuration expected :cpu)
                    backlog (observe-driver! configuration expected :backlog)]
      [cpu backlog])))

(comment ::shared-bound-driver-acquisition)
