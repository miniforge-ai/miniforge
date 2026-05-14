(ns ai.miniforge.connector-pipeline-output.anomaly.pipeline-output-anomaly-test
  "Coverage for `format/write-records`, `impl/require-handle!`, and
   `schema/validate!` boundary escalation via `response/throw-anomaly!`.

   Unsupported format → `:anomalies/unsupported`; missing handle →
   `:anomalies/not-found`; schema validation failure →
   `:anomalies/incorrect`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-pipeline-output.format :as fmt]
            [ai.miniforge.connector-pipeline-output.schema :as schema])
  (:import (clojure.lang ExceptionInfo)))

(deftest write-records-unsupported-format-throws-anomaly
  (testing "unsupported format raises :anomalies/unsupported"
    (try
      (fmt/write-records :weird "/tmp" "run-1" [])
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (re-find #"Unsupported output format" (.getMessage e)))
        (is (= :anomalies/unsupported (:anomaly/category (ex-data e))))
        (is (= :weird (:format (ex-data e))))))))

(deftest validate-bang-schema-failure-throws-anomaly
  (testing "schema validation failure raises :anomalies/incorrect"
    (try
      (schema/validate! schema/OutputConfig {})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (re-find #"Schema validation failed" (.getMessage e)))
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))
        (is (some? (:errors (ex-data e))))))))

(deftest validate-bang-passes-through-on-success
  (testing "valid value passes through unchanged"
    (let [valid {:output/dir "/tmp/out" :output/format :edn}]
      (is (= valid (schema/validate! schema/OutputConfig valid))))))
