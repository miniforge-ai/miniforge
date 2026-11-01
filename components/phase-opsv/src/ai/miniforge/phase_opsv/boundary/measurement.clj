;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.measurement
  "Prometheus response validation and numeric decoding at the external boundary."
  (:require [ai.miniforge.phase-opsv.measurement-schema :as schema]
            [ai.miniforge.phase-opsv.measurement-window :as window]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} decode [request response]
  (cond
    (not (m/validate schema/RangeResponse response)) (window/invalid-response)
    (> (count (get-in response [:data :result 0 :values])) (:measurement/max-samples request))
    (window/invalid-response)
    :else (try+ (window/decode request response)
            (catch Exception _ (window/invalid-response)))))

(comment ::external-range-response)
