;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-lifetime-test
  (:require [ai.miniforge.workflow.checkpoint-test-support :as checkpoints]
            [ai.miniforge.workflow.phase-test-support :as phases]
            [ai.miniforge.workflow.runner-lifetime-fixtures :as fixture]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each checkpoints/with-temp-checkpoint-root phases/with-workflow-phase-test-support)

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} preparation-failure-still-reaches-cleanup-with-original-cause
  (let [failure (Exception. "fixture preparation failure")
        result (fixture/execute {:prepare failure})]
    (is (identical? failure (:result result)))
    (is (= [:cleanup] (:calls result)))
    (is (identical? failure (get-in result [:cleanup :exception])))))

(deftest ^{:stratum 0} heartbeat-stop-failure-cannot-skip-post-workflow-cleanup
  (let [failure (Error. "fixture heartbeat stop failure")
        result (fixture/execute {:stop failure})]
    (is (identical? failure (:result result)))
    (is (= [:stop :stop :cleanup] (:calls result)))
    (is (= :failed (get-in result [:cleanup :output :execution/status])))))

(deftest ^{:stratum 0} cleanup-failure-cannot-mask-preparation-failure
  (let [primary (Exception. "fixture preparation failure")
        secondary (Exception. "fixture cleanup failure")
        result (fixture/execute {:prepare primary :cleanup secondary})]
    (is (identical? primary (:result result)))
    (is (= [secondary] (vec (.getSuppressed primary))))
    (is (= [:cleanup] (:calls result)))))

(deftest ^{:stratum 0} success-result-and-normal-cleanup-order-are-preserved
  (let [result (fixture/execute {})]
    (is (= :completed (get-in result [:result :execution/status])))
    (is (= [:stop :cleanup] (:calls result)))
    (is (= (:result result) (get-in result [:cleanup :output])))))

(comment ::public-runner-resource-lifetime)
