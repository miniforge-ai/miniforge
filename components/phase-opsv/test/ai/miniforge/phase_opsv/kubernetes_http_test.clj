;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-http-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [ai.miniforge.phase-opsv.kubernetes-http-test-support :as fixture]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [clojure.test :refer [deftest is]])
  (:import [java.net HttpURLConnection]
           [java.util.concurrent TimeoutException]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-native-json-read-preserves-explicit-read-only-options
  (let [[result requests] (fixture/read-response! {} (wire/response "{\"kind\":\"Namespace\"}"))
        options (first requests)]
    (is (= {"kind" "Namespace"} result))
    (is (= :get (:method options)))
    (is (= "https://cluster.invalid/api/v1/namespaces/staging" (:uri options)))
    (is (= transport/identity-coding (get-in options [:headers "Accept-Encoding"])))
    (is (true? (:raw options)))
    (is (true? (:async options)))))

(deftest ^{:stratum 0} test-absent-resource-is-not-json-null-or-a-successful-document
  (let [[missing] (fixture/read-response! {} (wire/response "" {:status transport/not-found-status}))
        [json-null] (fixture/read-response! {} (wire/response "null"))]
    (is (= response/not-found missing))
    (is (not= missing json-null))))

(deftest ^{:stratum 0} test-status-and-content-coding-refusals-are-sanitized
  (doseq [status [HttpURLConnection/HTTP_MOVED_PERM
                  HttpURLConnection/HTTP_UNAUTHORIZED
                  HttpURLConnection/HTTP_FORBIDDEN
                  HttpURLConnection/HTTP_INTERNAL_ERROR]]
    (let [[result] (fixture/read-response! {} (wire/response "secret-sentinel" {:status status}))]
      (is (= :unavailable (:anomaly/type result)))
      (is (= status (get-in result [:anomaly/data :http/status])))))
  (let [[result] (fixture/read-response! {} (wire/response "{}" {:encodings ["gzip"]}))]
    (is (anomaly/anomaly? result))
    (is (empty? (:anomaly/data result)))))

(deftest ^{:stratum 0} test-byte-limit-and-unambiguous-json-admission-are-enforced
  (let [[result] (fixture/read-response! {:kubernetes/max-response-bytes 1} (wire/response "{}"))]
    (is (anomaly/anomaly? result)))
  (doseq [text ["{}{}" "{\"kind\":1,\"kind\":2}" "not-json"]]
    (is (thrown? Exception (fixture/read-response! {} (wire/response text))))))

(deftest ^{:stratum 0} test-expired-captures-do-not-start-an-http-request
  (let [calls (atom [])
        pending (wire/completed (wire/response "{}"))]
    (is (thrown? TimeoutException (fixture/read-pending! {} pending 0 calls)))
    (is (empty? @calls))))

(deftest ^{:stratum 0} test-critical-http-causes-remain-visible-to-the-outer-boundary
  (doseq [failure [(AssertionError.) (InterruptedException.)]]
    (let [pending (wire/failed-future failure)
          operation (partial fixture/read-pending! {} pending (fixture/deadline))
          [thrown _] (wire/interrupted-result operation)]
      (is (identical? failure (cleanup/fatal-cause thrown))))))

(comment ::bounded-kubernetes-http-acceptance)
