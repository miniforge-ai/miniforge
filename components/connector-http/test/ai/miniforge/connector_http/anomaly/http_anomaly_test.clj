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
   and `request/throw-on-failure!` boundary escalation via
   `response/throw-anomaly!`.

   Config validation → `:anomalies/incorrect`. Handle not found →
   `:anomalies/not-found`. Request failure → `:anomalies/unavailable`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-http.impl :as impl]
            [ai.miniforge.connector-http.request :as request])
  (:import (clojure.lang ExceptionInfo)))

(deftest do-connect-missing-base-url-throws-anomaly
  (testing "missing :http/base-url raises :anomalies/incorrect"
    (try
      (impl/do-connect {:http/endpoint "/items"} nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))

(deftest do-connect-missing-endpoint-throws-anomaly
  (testing "missing :http/endpoint raises :anomalies/incorrect"
    (try
      (impl/do-connect {:http/base-url "https://x"} nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))

(deftest do-discover-missing-handle-throws-anomaly
  (testing "discover with bogus handle raises :anomalies/not-found"
    (try
      (impl/do-discover "no-such-handle")
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/not-found (:anomaly/category (ex-data e))))
        (is (= "no-such-handle" (:handle (ex-data e))))))))

(deftest do-extract-missing-handle-throws-anomaly
  (testing "extract with bogus handle raises :anomalies/not-found"
    (try
      (impl/do-extract "no-such-handle" {})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/not-found (:anomaly/category (ex-data e))))))))

(deftest throw-on-failure-unavailable-anomaly
  (testing "request failure raises :anomalies/unavailable"
    (try
      (request/throw-on-failure! {:success? false
                                  :error "boom"
                                  :error-type :transient})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/unavailable (:anomaly/category (ex-data e))))
        (is (= :transient (:error-type (ex-data e))))))))

(deftest throw-on-failure-passes-through-success
  (testing "successful result passes through unchanged"
    (let [success {:success? true :body :data}]
      (is (= success (request/throw-on-failure! success))))))

(deftest fetch-single-request-failure-throws-anomaly
  (testing "do-extract propagates fetch-single request failure as :anomalies/unavailable"
    (let [{:keys [connection/handle]} (impl/do-connect {:http/base-url "https://example.test"
                                                        :http/endpoint "/items"}
                                                       nil)]
      (with-redefs [impl/do-request
                    (fn [_url _headers _query-params]
                      {:success? false
                       :error "upstream down"
                       :error-type :transient})]
        (try
          (impl/do-extract handle {})
          (is false "should have thrown")
          (catch ExceptionInfo e
            (is (= :anomalies/unavailable (:anomaly/category (ex-data e))))
            (is (= :transient (:error-type (ex-data e)))))
          (finally
            (impl/do-close handle)))))))
