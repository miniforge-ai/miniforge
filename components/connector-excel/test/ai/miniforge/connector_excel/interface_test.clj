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

(ns ai.miniforge.connector-excel.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-excel.impl :as impl]))

(deftest cell-value-nil-test
  (testing "nil cell returns nil"
    (is (nil? (#'impl/cell-value nil)))))

(deftest parse-sheet-column-mapping-test
  (testing "parse-sheet extracts records with column mapping"
    ;; Create an in-memory workbook to test against
    (let [wb   (org.apache.poi.xssf.usermodel.XSSFWorkbook.)
          sheet (.createSheet wb "TestSheet")
          row0  (.createRow sheet 0)]
      ;; Header row (skipped by data-start-row)
      (-> (.createCell row0 0) (.setCellValue "Date"))
      (-> (.createCell row0 1) (.setCellValue "Value"))
      ;; Data rows
      (let [row1 (.createRow sheet 1)]
        (-> (.createCell row1 0) (.setCellValue 2025.01))
        (-> (.createCell row1 1) (.setCellValue 35.5)))
      (let [row2 (.createRow sheet 2)]
        (-> (.createCell row2 0) (.setCellValue 2025.02))
        (-> (.createCell row2 1) (.setCellValue 36.1)))

      (let [records (impl/parse-sheet wb "TestSheet" {0 :date 1 :value} 1 nil)]
        (is (= 2 (count records)))
        (is (= 2025.01 (:date (first records))))
        (is (= 35.5 (:value (first records))))
        (is (= 36.1 (:value (second records)))))
      (.close wb))))

(deftest parse-sheet-with-filter-test
  (testing "parse-sheet applies row filter"
    (let [wb    (org.apache.poi.xssf.usermodel.XSSFWorkbook.)
          sheet (.createSheet wb "Filtered")]
      (let [r (.createRow sheet 0)]
        (-> (.createCell r 0) (.setCellValue 2019.12))
        (-> (.createCell r 1) (.setCellValue 30.0)))
      (let [r (.createRow sheet 1)]
        (-> (.createCell r 0) (.setCellValue 2020.01))
        (-> (.createCell r 1) (.setCellValue 31.0)))
      (let [r (.createRow sheet 2)]
        (-> (.createCell r 0) (.setCellValue 2021.06))
        (-> (.createCell r 1) (.setCellValue 38.0)))

      (let [records (impl/parse-sheet wb "Filtered" {0 :date 1 :value} 0
                                       (fn [rec] (>= (:date rec) 2020.0)))]
        (is (= 2 (count records)))
        (is (= 2020.01 (:date (first records)))))
      (.close wb))))

(deftest parse-sheet-missing-sheet-test
  (testing "parse-sheet throws on missing sheet"
    (let [wb (org.apache.poi.xssf.usermodel.XSSFWorkbook.)]
      (.createSheet wb "Other")
      (is (thrown? Exception (impl/parse-sheet wb "Missing" {0 :x} 0 nil)))
      (.close wb))))

(deftest extract-enriches-series-id-test
  (testing "do-extract enriches records with series-id"
    (let [wb    (org.apache.poi.xssf.usermodel.XSSFWorkbook.)
          sheet (.createSheet wb "Data")]
      (let [r (.createRow sheet 0)]
        (-> (.createCell r 0) (.setCellValue 2025.01))
        (-> (.createCell r 1) (.setCellValue 42.0)))

      (let [handle (str (java.util.UUID/randomUUID))]
        (impl/store-handle! handle {:config   {:excel/sheet-name "Data"
                                               :excel/columns    {0 :date 1 :value}
                                               :excel/data-start-row 0
                                               :excel/series-id  "TEST_SERIES"}
                                    :workbook wb
                                    :tmp-file nil})
        (let [result (impl/do-extract handle {})]
          (is (= 1 (count (:records result))))
          (is (= "TEST_SERIES" (:series_id (first (:records result))))))
        (impl/remove-handle! handle))
      (.close wb))))

(deftest extract-decimal-year-date-format-test
  (testing "do-extract normalizes decimal-year dates to ISO"
    (let [wb    (org.apache.poi.xssf.usermodel.XSSFWorkbook.)
          sheet (.createSheet wb "Data")]
      (let [r (.createRow sheet 0)]
        (-> (.createCell r 0) (.setCellValue 2026.03))
        (-> (.createCell r 1) (.setCellValue 37.5)))

      (let [handle (str (java.util.UUID/randomUUID))]
        (impl/store-handle! handle {:config   {:excel/sheet-name  "Data"
                                               :excel/columns     {0 :date 1 :value}
                                               :excel/data-start-row 0
                                               :excel/date-format :decimal-year
                                               :excel/series-id   "CAPE10"}
                                    :workbook wb
                                    :tmp-file nil})
        (let [result (impl/do-extract handle {})
              rec    (first (:records result))]
          (is (= "2026-03-01" (:date rec)))
          (is (= "CAPE10" (:series_id rec))))
        (impl/remove-handle! handle))
      (.close wb))))

(deftest connect-validates-config-test
  (testing "do-connect requires url, sheet-name, columns"
    (is (thrown? Exception (impl/do-connect {} nil)))
    (is (thrown? Exception (impl/do-connect {:excel/url "x"} nil)))
    (is (thrown? Exception (impl/do-connect {:excel/url "x" :excel/sheet-name "S"} nil)))))
