;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-execution-outcomes-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.clock.interface :as clock]
            [ai.miniforge.workflow.chain-execution-test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} failed-events
  [:chain/started :chain/step-started :chain/step-failed :chain/failed])

(deftest ^{:stratum 0} backwards-clock-does-not-produce-negative-durations
  (let [ticks (atom 100)]
    (with-redefs [clock/now-ms (partial swap! ticks dec)]
      (let [{:keys [outcome]} (support/observed-run support/loaded-workflow {:execution/status :completed})]
        (is (= :completed (:chain/status outcome)))
        (is (zero? (:chain/duration-ms outcome)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} missing-workflow-terminates-the-started-step
  (let [{:keys [outcome calls events]} (support/observed-run support/missing-workflow nil)]
    (is (= :failed (:chain/status outcome)))
    (is (zero? calls))
    (is (= failed-events events))))

(deftest ^{:stratum 1} only-completed-child-results-advance-the-chain
  (doseq [result [nil {} false 42 {:status :completed}
                  {:execution/status :cancelled} {:execution/status :running}
                  (Exception. "child exception")
                  (anomaly/anomaly :fault "runner refused" {})
                  (assoc (anomaly/anomaly :fault "misleading success" {}) :execution/status :completed)]]
    (let [{:keys [outcome calls events]} (support/observed-run support/loaded-workflow result)]
      (is (= :failed (:chain/status outcome)))
      (is (= 1 calls))
      (is (= failed-events events)))))

(deftest ^{:stratum 1} returned-loading-anomalies-do-not-reach-the-runner
  (let [fault (anomaly/anomaly :not-found "missing definition" {})
        {:keys [outcome calls events]} (support/observed-run (constantly fault) nil)]
    (is (= :failed (:chain/status outcome)))
    (is (zero? calls))
    (is (= failed-events events))))

(deftest ^{:stratum 1} binding-errors-terminate-before-child-execution
  (let [definition (assoc-in (support/definition) [:chain/steps 0 :step/input-bindings] 42)]
    (with-redefs [support/definition (constantly definition)]
      (let [{:keys [outcome calls events]} (support/observed-run support/loaded-workflow nil)]
        (is (= :failed (:chain/status outcome)))
        (is (zero? calls))
        (is (= failed-events events))))))

(comment
  (support/definition))
