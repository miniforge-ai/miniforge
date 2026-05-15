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

(ns ai.miniforge.connector-edgar.anomaly.edgar-anomaly-test
  "Coverage for `impl/do-connect` boundary escalation via
   `response/throw-anomaly!`.

   Missing config keys → `:anomalies/incorrect`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-edgar.impl :as impl])
  (:import (clojure.lang ExceptionInfo)))

(deftest do-connect-missing-form-type-throws-anomaly
  (testing "missing :edgar/form-type raises :anomalies/incorrect"
    (try
      (impl/do-connect {:edgar/user-agent "ua" :edgar/aggregation :monthly-buy-sell-ratio} nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))
        (is (some? (:config (ex-data e))))))))

(deftest do-connect-missing-user-agent-throws-anomaly
  (testing "missing :edgar/user-agent raises :anomalies/incorrect"
    (try
      (impl/do-connect {:edgar/form-type "10-K" :edgar/aggregation :monthly-buy-sell-ratio} nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))

(deftest do-connect-missing-aggregation-throws-anomaly
  (testing "missing :edgar/aggregation raises :anomalies/incorrect"
    (try
      (impl/do-connect {:edgar/form-type "10-K" :edgar/user-agent "ua"} nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))
