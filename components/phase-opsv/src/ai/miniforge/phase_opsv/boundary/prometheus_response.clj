;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.prometheus-response
  "Read bounded bytes and admit the native Prometheus response at ingress."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.prometheus-body :as body]
            [ai.miniforge.phase-opsv.boundary.prometheus-io :as io]
            [ai.miniforge.phase-opsv.boundary.prometheus-http :as http]
            [ai.miniforge.phase-opsv.measurement-window :as window]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} timeout []
  (anomaly/anomaly :timeout (msg/ts :prometheus/timeout) {}))

(defn ^{:stratum 0} observe! [settings request until pending]
  (let [response (http/response-fields (io/await! pending until))
        {:keys [status encodings body]} response]
    (cond
      (not= 200 status) (anomaly/anomaly :unavailable (msg/ts :prometheus/http-status) {:http/status status})
      (not (http/unencoded? encodings)) (window/invalid-response)
      :else (body/decode! settings request until body))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} acquire! [settings request]
  (let [until (io/deadline (:prometheus/timeout-ms settings))
        pending (http/start! settings request)
        operation (partial observe! settings request until pending)
        result (io/owned-result! operation (partial http/release! pending))]
    (if (pos? (io/remaining-ms until)) result (timeout))))

(comment ::external-prometheus-body)
