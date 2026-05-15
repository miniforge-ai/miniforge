(ns ai.miniforge.connector-gitlab.anomaly.gitlab-anomaly-test
  "Coverage for `impl/do-connect`, `impl/require-resource!`, and
   `schema/validate!` boundary escalation via `response/throw-anomaly!`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-gitlab.impl :as impl]
            [ai.miniforge.connector-gitlab.schema :as schema])
  (:import (clojure.lang ExceptionInfo)))

(deftest do-connect-missing-project-throws-anomaly
  (testing "config without project-id or project-path raises :anomalies/incorrect"
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
      (schema/validate! schema/GitLabConfig {:gitlab/project-id "x" :gitlab/issue-iid "not-an-int"})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))
        (is (some? (:errors (ex-data e))))))))
