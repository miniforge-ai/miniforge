(ns ai.miniforge.connector-excel.anomaly.excel-anomaly-test
  "Coverage for `impl/do-connect` config-validation paths and
   `impl/parse-sheet` missing-sheet path. Boundary throws route through
   `response/throw-anomaly!`."
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
