;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.prometheus-http
  "Construct bounded Prometheus range requests on the shared HTTP transport."
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} query-parameters [settings request]
  (let [timeout (str (:prometheus/timeout-ms settings) "ms")]
    {:query (:prometheus/query request)
     :start (:window/start-seconds request)
     :end (:window/end-seconds request)
     :step (:window/step-seconds request)
     :timeout timeout
     :limit 2}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} request-options [settings request]
  (let [base (str/replace (:prometheus/base-url settings) #"/+$" "")
        uri (str base "/api/v1/query_range")
        params (query-parameters settings request)]
    (transport/json-get-options uri (:prometheus/bearer-token settings)
                                (:prometheus/timeout-ms settings) params)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start! [settings request]
  (transport/start! (request-options settings request)))

(comment ::read-only-prometheus-http)
