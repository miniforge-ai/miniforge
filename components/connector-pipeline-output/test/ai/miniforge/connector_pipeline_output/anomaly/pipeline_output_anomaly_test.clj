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

(ns ai.miniforge.connector-pipeline-output.anomaly.pipeline-output-anomaly-test
  "Coverage for `format/write-records` and
   `schema/validate!` boundary escalation via `response/throw-anomaly!`.

   Unsupported format → `:anomalies/unsupported`; schema validation failure →
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
