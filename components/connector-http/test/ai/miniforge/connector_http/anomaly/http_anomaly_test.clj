;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.connector-http.anomaly.http-anomaly-test
  "Coverage for `impl/do-connect`, `impl/do-discover`, `impl/do-extract`,
   and `request/throw-on-failure!` anomaly behavior.

   Config validation → `:anomalies/incorrect`. Handle not found →
   `:anomalies/not-found`. Request failure → `:anomalies/unavailable`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-http.impl :as impl]
            [ai.miniforge.connector-http.request :as request]
            [ai.miniforge.response.interface :as response])
  (:import (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} do-connect-missing-base-url-returns-anomaly
  (testing "missing :http/base-url returns :anomalies/incorrect"
    (let [result (impl/do-connect {:http/endpoint "/items"} nil)]
      (is (= :anomalies/incorrect (:anomaly/category result))))))

(deftest ^{:stratum 0} do-connect-missing-endpoint-returns-anomaly
  (testing "missing :http/endpoint returns :anomalies/incorrect"
    (let [result (impl/do-connect {:http/base-url "https://x"} nil)]
      (is (= :anomalies/incorrect (:anomaly/category result))))))

(deftest ^{:stratum 0} do-discover-missing-handle-returns-anomaly
  (testing "discover with bogus handle returns :anomalies/not-found"
    (let [result (impl/do-discover "no-such-handle")]
      (is (response/anomaly-map? result))
      (is (= :anomalies/not-found (:anomaly/category result)))
      (is (= "no-such-handle" (:handle result))))))

(deftest ^{:stratum 0} do-extract-missing-handle-returns-anomaly
  (testing "extract with bogus handle returns :anomalies/not-found"
    (let [result (impl/do-extract "no-such-handle" {})]
      (is (= :anomalies/not-found (:anomaly/category result))))))

(deftest ^{:stratum 0} throw-on-failure-unavailable-anomaly
  (testing "request failure raises :anomalies/unavailable"
    (try
      (request/throw-on-failure! {:success? false
                                  :error "boom"
                                  :error-type :transient})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/unavailable (:anomaly/category (ex-data e))))
        (is (= :transient (:error-type (ex-data e))))))))

(deftest ^{:stratum 0} throw-on-failure-passes-through-success
  (testing "successful result passes through unchanged"
    (let [success {:success? true :body :data}]
      (is (= success (request/throw-on-failure! success))))))

(deftest ^{:stratum 0} fetch-single-request-failure-returns-anomaly
  (testing "do-extract returns fetch-single request failure as :anomalies/unavailable"
    (let [{:keys [connection/handle]} (impl/do-connect {:http/base-url "https://example.test"
                                                        :http/endpoint "/items"}
                                                       nil)]
      (with-redefs [impl/do-request
                    (fn [_url _headers _query-params]
                      {:success? false
                       :error "upstream down"
                       :error-type :transient})]
        (let [result (impl/do-extract handle {})]
          (is (response/anomaly-map? result))
          (is (= :anomalies/unavailable (:anomaly/category result)))
          (is (= :transient (:error-type result))))
        (impl/do-close handle)))))
