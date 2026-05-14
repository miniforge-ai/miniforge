(ns ai.miniforge.connector-edgar.anomaly.edgar-anomaly-test
  "Coverage for `impl/do-connect` and `impl/do-extract` boundary
   escalation via `response/throw-anomaly!`.

   Missing config keys → `:anomalies/incorrect`. Unknown aggregation →
   `:anomalies/unsupported`."
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
