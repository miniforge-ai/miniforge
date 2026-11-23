;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.prometheus-criteria
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.prometheus-client :as client]
            [ai.miniforge.phase-opsv.boundary.prometheus-criteria-request :as request]
            [ai.miniforge.phase-opsv.kubernetes-signal-model :as labels]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.prometheus-criteria :as criteria]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} observe-criterion! [settings expected criterion]
  (anomaly/let-ok [observed (client/observe-window! settings (:measurement/request criterion))]
    (if (labels/matches? expected observed)
      (criteria/record criterion observed)
      (anomaly/anomaly :conflict (msg/ts :discovery/unbound-signal) {}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} collect! [settings expected records criterion]
  (let [observed (observe-criterion! settings expected criterion)]
    (if (anomaly/anomaly? observed) (reduced observed) (conj records observed))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe! [configuration]
  (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
  (anomaly/let-ok [admitted (request/admit configuration)]
    (let [settings (:measurement/prometheus admitted)
          expected (criteria/expected-labels admitted)
          declared (:measurement/criteria admitted)
          records (reduce (partial collect! settings expected) [] declared)]
      (anomaly/let-ok [observations records] (criteria/result admitted observations)))))

(comment ::read-only-observations-with-original-window-provenance)
