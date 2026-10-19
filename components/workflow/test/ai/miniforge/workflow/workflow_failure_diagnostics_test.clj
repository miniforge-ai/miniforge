;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.workflow-failure-diagnostics-test
  (:require [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.runner-failure-event :as facts]
            [ai.miniforge.workflow.workflow-failure-diagnostics-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} unrelated-inner-diagnostics-do-not-classify-the-summary
  (let [data (facts/data (support/summary-failure "Different failure"))]
    (is (= :failure.class/unknown (get-in data [:options :failure/class])))
    (is (= "Different failure" (get-in data [:error :message])))))

(deftest ^{:stratum 0} gate-fallback-does-not-replace-a-later-execution-error
  (let [failed (assoc (support/gate-failure) :execution/errors [{:message "Budget exhausted"}])
        data (facts/data failed)]
    (is (= :failure.class/resource (get-in data [:options :failure/class])))
    (is (= "Budget exhausted" (get-in data [:error :message])))
    (is (not (contains? (:error data) :anomaly)))))

(deftest ^{:stratum 0} unrelated-or-superseded-gate-chain-entries-are-not-reused
  (let [failed (support/gate-failure)
        original (:execution/response-chain failed)
        superseded (response/add-success original :verify {})
        unrelated (assoc-in original [:response-chain 0 :operation] :other-phase)]
    (doseq [chain [superseded unrelated]]
      (let [data (facts/data (assoc failed :execution/response-chain chain))]
        (is (= :failure.class/unknown (get-in data [:options :failure/class])))
        (is (not (contains? (:error data) :anomaly)))))))

(deftest ^{:stratum 0} matching-native-summary-retains-inner-exception-provenance
  (journal/with-stream support/verify-summary))

(deftest ^{:stratum 0} gate-only-failure-retains-the-recorded-policy-diagnostic
  (journal/with-stream support/verify-gate))
