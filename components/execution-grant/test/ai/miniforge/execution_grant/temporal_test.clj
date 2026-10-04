;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.temporal-test
  (:require [ai.miniforge.execution-grant.temporal :as temporal]
            [clojure.test :refer [deftest is]])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} sql-date-and-time-use-epoch-milliseconds-test
  (doseq [epoch-millis [-86400001 123456789]
          value [(java.sql.Date. epoch-millis) (java.sql.Time. epoch-millis)]]
    (is (inst? value))
    (is (= (Instant/ofEpochMilli epoch-millis) (temporal/->instant value)))))

(deftest ^{:stratum 0} sql-timestamp-preserves-nanosecond-precision-test
  (let [instant (Instant/parse "2026-09-27T00:00:00.123456789Z")]
    (is (= instant (temporal/->instant (java.sql.Timestamp/from instant))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.execution-grant.temporal-test))
