(ns ai.miniforge.connector-jira.anomaly.jira-anomaly-test
  "Coverage for `impl/do-connect`, `impl/require-resource!`, and
   `schema/validate!` boundary escalation via `response/throw-anomaly!`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-jira.impl :as impl]
            [ai.miniforge.connector-jira.schema :as schema])
  (:import (clojure.lang ExceptionInfo)))

(deftest do-connect-missing-site-throws-anomaly
  (testing "config without :jira/site raises :anomalies/incorrect"
    (try
      (impl/do-connect {} nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))

(deftest require-resource-unknown-throws-anomaly
  (testing "looking up an unknown resource raises :anomalies/not-found"
    (try
      (@#'impl/require-resource! "totally-bogus-resource")
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/not-found (:anomaly/category (ex-data e))))))))

(deftest schema-validate-bang-failure-throws-anomaly
  (testing "schema validation failure raises :anomalies/incorrect"
    (try
      (schema/validate! schema/JiraConfig {:jira/site 42})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))
        (is (some? (:errors (ex-data e))))))))
