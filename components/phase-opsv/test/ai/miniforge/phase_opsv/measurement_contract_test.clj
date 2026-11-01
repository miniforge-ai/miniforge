;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.measurement-contract-test
  (:require [ai.miniforge.phase-opsv.measurement-test-support :as fixture]
            [ai.miniforge.phase-opsv.interface :as observation]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} declared-query-grid-and-real-zero-samples-are-preserved
  (let [request (fixture/request)
        measured (observation/decode-measurement-window request (fixture/response))]
    (is (= (:prometheus/query request) (:prometheus/query measured)))
    (is (= (select-keys request [:window/start-seconds :window/end-seconds :window/step-seconds])
           (:measurement/window measured)))
    (is (= {"namespace" "catalog"} (:measurement/labels measured)))
    (is (= [[100 2.0] [110 3.0] [120 0.0]] (:measurement/samples measured)))))

(deftest ^{:stratum 0} numeric-json-timestamps-need-not-use-integer-java-types
  (let [series (fixture/series [[100.0 "1"] [110.0 "2"] [120.0 "3"]])
        result (observation/decode-measurement-window (fixture/request) (fixture/response [series]))]
    (is (= [[100.0 1.0] [110.0 2.0] [120.0 3.0]] (:measurement/samples result)))))

(deftest ^{:stratum 0} native-json-reader-output-preserves-metric-label-identity
  (let [wire (json/generate-string (fixture/response))
        response (json/parse-string wire false)
        measured (observation/decode-measurement-window (fixture/request) response)]
    (is (= (get-in response ["data" "result" 0 "metric"]) (:measurement/labels measured)))
    (is (= [[100 2.0] [110 3.0] [120 0.0]] (:measurement/samples measured)))))

(comment ::bounded-observation-admission)
