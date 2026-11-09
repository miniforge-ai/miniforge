;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.measurement-test-support)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} request
  ([] (request {}))
  ([overrides]
   (merge {:prometheus/query "sum(queue_depth{namespace=\"catalog\"})"
           :window/start-seconds 100
           :window/end-seconds 120
           :window/step-seconds 10
           :measurement/max-samples 3}
          overrides)))

(defn ^{:stratum 0} series
  ([] (series [[100 "2"] [110 "3"] [120 "0"]]))
  ([values]
   {"metric" {"namespace" "catalog"}
    "values" values}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} response
  ([] (response [(series)]))
  ([results]
   {"status" "success"
    "data" {"resultType" "matrix"
            "result" results}}))

(comment ::prometheus-api-range-vector-shape)
