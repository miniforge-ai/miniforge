;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.measurement
  "Prometheus response validation and numeric decoding at the external boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.measurement-schema :as schema]
            [ai.miniforge.phase-opsv.measurement-window :as window]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} decode-response [request response]
  (cond
    (not (m/validate schema/RangeResponse response)) (window/invalid-response)
    (> (count (get-in response ["data" "result" 0 "values"])) (:measurement/max-samples request))
    (window/invalid-response)
    :else (try+ (window/decode request response)
            (catch Exception _ (window/invalid-response)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} decode-window
  "Decode a parsed Prometheus response against a predeclared bounded grid.
   Response fields and labels retain native JSON string keys. Require one
   complete float series without API warnings; return observations, never
   a verification verdict or execution authority."
  [request response]
  (if (and (m/validate schema/WindowRequest request) (window/bounded? request))
    (decode-response request response)
    (anomaly/anomaly :invalid-input (msg/ts :measurement/invalid-request) {})))

(comment ::external-range-response)
