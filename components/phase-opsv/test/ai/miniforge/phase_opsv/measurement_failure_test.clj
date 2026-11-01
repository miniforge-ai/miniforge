;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.measurement-failure-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.measurement-test-support :as fixture]
            [ai.miniforge.phase-opsv.interface :as observation]
            [ai.miniforge.phase-opsv.measurement-schema :as schema]
            [malli.core :as m]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} recording-validator [calls validate schema value & options]
  (swap! calls conj schema)
  (apply validate schema value options))

(deftest ^{:stratum 0} malformed-or-unbounded-requests-are-anomalies-not-exceptions
  (doseq [request [nil {} (fixture/request {:prometheus/query " "})
                    (fixture/request {:prometheus/query "\u00a0"})
                    (fixture/request {:window/step-seconds 0})
                    (fixture/request {:window/start-seconds 120})
                    (fixture/request {:window/end-seconds 99})
                    (fixture/request {:measurement/max-samples 2})]]
    (is (anomaly/anomaly? (observation/decode-measurement-window request (fixture/response))))))

(deftest ^{:stratum 0} missing-data-and-ambiguous-series-cannot-masquerade-as-zero
  (doseq [response [(fixture/response [])
                     (fixture/response [(fixture/series) (fixture/series)])
                     (fixture/response [(fixture/series [[100 "0"] [120 "0"]])])
                     (assoc (fixture/response) "warnings" ["partial data"])]]
    (is (anomaly/anomaly? (observation/decode-measurement-window (fixture/request) response)))))

(deftest ^{:stratum 0} only-complete-finite-float-windows-can-support-conclusions
  (doseq [values [[[100 "NaN"] [110 "3"] [120 "4"]]
                  [[100 "+Inf"] [110 "3"] [120 "4"]]
                  [[100 "-Inf"] [110 "3"] [120 "4"]]
                  [[100 "secret-shaped-invalid-number"] [110 "3"] [120 "4"]]
                  [[100 "1"] [110 "3"] [121 "4"]]
                  [[100 "1"] [100 "3"] [120 "4"]]
                  [[120 "1"] [110 "3"] [100 "4"]]
                  [[100 "1"] [110 "3"] [120 "4"] [130 "5"]]
                  [[100.0000000001M "1"] [110 "3"] [120 "4"]]]]
    (let [response (fixture/response [(fixture/series values)])
          result (observation/decode-measurement-window (fixture/request) response)]
      (is (anomaly/anomaly? result))
      (is (not (.contains (pr-str result) "secret-shaped"))))))

(deftest ^{:stratum 0} unsupported-or-contradictory-wire-shapes-are-refused
  (doseq [response [nil false [] {}
                     (assoc (fixture/response) "status" "error")
                     (assoc (fixture/response) "error" "query failed")
                     (assoc (fixture/response) "errorType" nil)
                     (assoc (fixture/response) "infos" ["mixed samples"])
                     (assoc-in (fixture/response) ["data" "resultType"] "vector")
                     (assoc-in (fixture/response) ["data" "result" 0 "histograms"] [[100 {}]])
                     (assoc-in (fixture/response) ["data" "result" 0 "values"] [])]]
    (is (anomaly/anomaly? (observation/decode-measurement-window (fixture/request) response)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} oversized-sample-vectors-are-rejected-before-element-validation
  (let [calls (atom [])
        validate m/validate
        series (fixture/series [[100 "1"] [110 "2"] [120 "3"] [130 "4"]])
        response (fixture/response [series])]
    (with-redefs [m/validate (partial recording-validator calls validate)]
      (is (anomaly/anomaly? (observation/decode-measurement-window (fixture/request) response))))
    (is (not-any? #{schema/RangeResponse} @calls))))

(comment ::bounded-observation-admission)
