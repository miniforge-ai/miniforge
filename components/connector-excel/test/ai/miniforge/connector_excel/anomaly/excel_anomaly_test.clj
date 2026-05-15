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
  "Coverage for `impl/do-connect` config-validation paths,
   `impl/parse-sheet` sheet-not-found, and the non-2xx download path
   exercised through `impl/do-connect`. Boundary throws route through
   `response/throw-anomaly!`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-excel.impl :as impl]
            [babashka.http-client :as http])
  (:import (clojure.lang ExceptionInfo)
           (org.apache.poi.xssf.usermodel XSSFWorkbook)))

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

(deftest parse-sheet-missing-sheet-throws-anomaly
  (testing "requesting a sheet absent from the workbook raises :anomalies/not-found"
    (let [wb (XSSFWorkbook.)]
      (try
        (.createSheet wb "Existing")
        (try
          (impl/parse-sheet wb "Missing" {0 :a} 0 nil)
          (is false "should have thrown")
          (catch ExceptionInfo e
            (is (= :anomalies/not-found (:anomaly/category (ex-data e))))
            (is (= "Missing" (:sheet (ex-data e))))))
        (finally
          (.close wb))))))

(deftest do-connect-non-2xx-download-throws-anomaly
  (testing "non-2xx HTTP response while downloading raises :anomalies/unavailable"
    (with-redefs [http/get (fn [_url _opts] {:status 503 :body (byte-array 0)})]
      (try
        (impl/do-connect {:excel/url "http://x/y.xls"
                          :excel/sheet-name "Sheet1"
                          :excel/columns {0 :a}}
                         nil)
        (is false "should have thrown")
        (catch ExceptionInfo e
          (is (= :anomalies/unavailable (:anomaly/category (ex-data e))))
          (is (= 503 (:status (ex-data e)))))))))
