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

(ns ai.miniforge.connector-excel.anomaly.excel-anomaly-test
  "Coverage for `impl/do-connect` config-validation paths. Boundary throws
   route through `response/throw-anomaly!`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-excel.impl :as impl])
  (:import (clojure.lang ExceptionInfo)))

(deftest do-connect-missing-url-throws-anomaly
  (testing "missing :excel/url raises :anomalies/incorrect"
    (try
      (impl/do-connect {:excel/sheet-name "Sheet1" :excel/columns {0 :a}} nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))

(deftest do-connect-missing-sheet-throws-anomaly
  (testing "missing :excel/sheet-name raises :anomalies/incorrect"
    (try
      (impl/do-connect {:excel/url "http://x/y.xls" :excel/columns {0 :a}} nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))

(deftest do-connect-missing-columns-throws-anomaly
  (testing "missing :excel/columns raises :anomalies/incorrect"
    (try
      (impl/do-connect {:excel/url "http://x/y.xls" :excel/sheet-name "Sheet1"} nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))
