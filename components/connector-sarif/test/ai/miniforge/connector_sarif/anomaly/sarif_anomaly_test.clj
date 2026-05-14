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

(ns ai.miniforge.connector-sarif.anomaly.sarif-anomaly-test
  "Coverage for sarif `format/parse-file` and `impl/do-connect`
   boundary escalation via `response/throw-anomaly!`.

   Unsupported format → `:anomalies/unsupported`; invalid config →
   `:anomalies/incorrect`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-sarif.format :as fmt]
            [ai.miniforge.connector-sarif.impl :as impl])
  (:import (clojure.lang ExceptionInfo)))

(deftest parse-file-unsupported-format-throws-anomaly
  (testing "unrecognised format raises :anomalies/unsupported"
    (try
      (fmt/parse-file "/nope/no.weird" :weird nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (re-find #"Unsupported format" (.getMessage e)))
        (is (= :anomalies/unsupported (:anomaly/category (ex-data e))))
        (is (= "/nope/no.weird" (:path (ex-data e))))
        (is (= :weird (:format (ex-data e))))))))

(deftest do-connect-invalid-config-throws-anomaly
  (testing "invalid SARIF config raises :anomalies/incorrect"
    (try
      (impl/do-connect {})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (re-find #"Invalid SARIF config" (.getMessage e)))
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))
        (is (seq (:errors (ex-data e))))))))
