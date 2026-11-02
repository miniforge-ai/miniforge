;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.prometheus-client
  "Read-only observation ingress; it grants no experimental or mutation authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.measurement :as measurement]
            [ai.miniforge.phase-opsv.boundary.prometheus-http :as http]
            [ai.miniforge.phase-opsv.boundary.prometheus-response :as response]
            [ai.miniforge.phase-opsv.boundary.prometheus-settings :as settings]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failed! [thrown]
  (cond
    (cleanup/fatal-cause thrown) (cleanup/after-failure! thrown (constantly nil))
    (http/timeout-cause thrown) (response/timeout)
    :else (anomaly/anomaly :unavailable (msg/ts :prometheus/unavailable) {})))

(defn- ^{:stratum 0} admitted-observation! [overrides request]
  (let [options (settings/load-settings overrides)
        failure (measurement/request-failure request)]
    (cond
      (anomaly/anomaly? options) options
      failure failure
      (> (:measurement/max-samples request) (:measurement/max-samples options)) (measurement/invalid-request)
      :else (response/acquire! options request))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe-window!
  "Read a declared window from explicit Prometheus settings. Never follow
   redirects, discover credentials, issue grants, or run an experiment."
  [overrides request]
  (try+
    (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
    (admitted-observation! overrides request)
    (catch Object _ (failed! (:throwable &throw-context)))))

(comment ::read-only-metric-window)
