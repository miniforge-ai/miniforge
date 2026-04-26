;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.connector-retry.circuit-breaker-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.connector-retry.circuit-breaker :as breaker]))

(deftest create-circuit-breaker-test
  (testing "uses EDN-backed defaults when opts are omitted"
    (let [created (breaker/create-circuit-breaker {})]
      (is (= :closed (:breaker/state created)))
      (is (= 5 (:breaker/failure-threshold created)))
      (is (= 60000 (:breaker/reset-timeout-ms created))))))

(deftest breaker-state-transition-test
  (testing "closed breakers remain closed until the threshold is reached"
    (let [initial (breaker/create-circuit-breaker {:breaker/failure-threshold 3
                                                   :breaker/reset-timeout-ms 5000})
          after-first-failure (breaker/record-failure initial)
          after-second-failure (breaker/record-failure after-first-failure)
          after-third-failure (breaker/record-failure after-second-failure)]
      (is (= :closed (:breaker/state after-first-failure)))
      (is (= :closed (:breaker/state after-second-failure)))
      (is (= :open (:breaker/state after-third-failure)))))

  (testing "open breakers become half-open only after the reset timeout"
    (let [now-ms (System/currentTimeMillis)
          open-breaker {:breaker/state :open
                        :breaker/failure-count 3
                        :breaker/failure-threshold 3
                        :breaker/reset-timeout-ms 1000
                        :breaker/last-failure-at now-ms
                        :breaker/last-success-at nil}
          expired-breaker (assoc open-breaker
                                 :breaker/last-failure-at (- now-ms 2000))]
      (is (= :open (breaker/breaker-state open-breaker now-ms)))
      (is (= :half-open (breaker/breaker-state expired-breaker now-ms)))))

  (testing "half-open failure reopens the breaker"
    (let [now-ms (System/currentTimeMillis)
          half-open-breaker {:breaker/state :open
                             :breaker/failure-count 3
                             :breaker/failure-threshold 3
                             :breaker/reset-timeout-ms 1000
                             :breaker/last-failure-at (- now-ms 2000)
                             :breaker/last-success-at nil}
          reopened (breaker/record-failure half-open-breaker)]
      (is (= :open (:breaker/state reopened)))
      (is (= 3 (:breaker/failure-count reopened))))))

(deftest request-gating-test
  (testing "requests are blocked only while the breaker is still open"
    (let [now-ms (System/currentTimeMillis)
          open-breaker {:breaker/state :open
                        :breaker/failure-count 5
                        :breaker/failure-threshold 5
                        :breaker/reset-timeout-ms 1000
                        :breaker/last-failure-at now-ms
                        :breaker/last-success-at nil}
          expired-breaker (assoc open-breaker
                                 :breaker/last-failure-at (- now-ms 2000))]
      (is (not (breaker/allow-request? open-breaker now-ms)))
      (is (breaker/allow-request? expired-breaker now-ms)))))
