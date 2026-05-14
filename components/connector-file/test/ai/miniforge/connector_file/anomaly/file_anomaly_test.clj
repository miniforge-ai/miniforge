(ns ai.miniforge.connector-file.anomaly.file-anomaly-test
  "Coverage for `impl/do-connect` config validation + `impl/do-extract`
   not-found path. Boundary throws route through `response/throw-anomaly!`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-file.impl :as impl])
  (:import (clojure.lang ExceptionInfo)))

(deftest do-connect-missing-path-throws-anomaly
  (testing "missing :file/path raises :anomalies/incorrect"
    (try
      (impl/do-connect {:file/format :csv})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))

(deftest do-connect-missing-format-throws-anomaly
  (testing "missing :file/format raises :anomalies/incorrect"
    (try
      (impl/do-connect {:file/path "/tmp/x.csv"})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))

(deftest do-connect-unsupported-format-throws-anomaly
  (testing "unsupported :file/format raises :anomalies/unsupported"
    (try
      (impl/do-connect {:file/path "/tmp/x.bin" :file/format :weird})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/unsupported (:anomaly/category (ex-data e))))
        (is (= :weird (:format (ex-data e))))))))

(deftest do-extract-nonexistent-file-throws-anomaly
  (testing "extract from nonexistent path raises :anomalies/not-found"
    (let [{:keys [connect/handle]} (impl/do-connect {:file/path "/no/such/file.csv"
                                                     :file/format :csv})]
      (try
        (impl/do-extract handle {})
        (is false "should have thrown")
        (catch ExceptionInfo e
          (is (= :anomalies/not-found (:anomaly/category (ex-data e)))))))))
