(ns ai.miniforge.connector-github.anomaly.github-anomaly-test
  "Coverage for `impl/validate-connect!` and `impl/require-resource!`
   boundary escalation via `response/throw-anomaly!`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-github.impl :as impl])
  (:import (clojure.lang ExceptionInfo)))

(deftest do-connect-missing-owner-and-org-throws-anomaly
  (testing "config without :github/owner or :github/org raises :anomalies/incorrect"
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
        (is (= :anomalies/not-found (:anomaly/category (ex-data e))))
        (is (= "totally-bogus-resource" (:resource (ex-data e))))))))
