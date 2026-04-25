;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.workflow.configurable-defaults-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.configurable-defaults :as defaults]))

(deftest max-phases-test
  (testing "returns a positive integer"
    (is (pos-int? (defaults/max-phases)))))

(deftest default-inner-loop-iterations-test
  (testing "returns a positive integer"
    (is (pos-int? (defaults/default-inner-loop-iterations)))))

(deftest default-phase-metrics-test
  (testing "returns the default metrics map"
    (is (= {:tokens 0
            :cost-usd 0.0
            :duration-ms 0}
           (defaults/default-phase-metrics)))))
