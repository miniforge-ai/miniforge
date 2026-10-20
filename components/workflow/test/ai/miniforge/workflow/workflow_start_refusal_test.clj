;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.workflow-start-refusal-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.checkpoint-test-support :as checkpoints]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.phase-test-support :as fixture]
            [ai.miniforge.workflow.workflow-start-test-support :as support]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each checkpoints/with-temp-checkpoint-root fixture/with-workflow-phase-test-support)

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} missing-intent-stops-before-any-phase-and-persists-failure
  (journal/with-stream support/verify-missing-intent))

(deftest ^{:stratum 0} closed-journal-stops-before-any-phase-and-persists-failure
  (journal/with-stream support/verify-closed))

(deftest ^{:stratum 0} constructor-exception-is-a-refusal-not-a-warning-only-success
  (journal/with-stream support/verify-construction))

(deftest ^{:stratum 0} fatal-startup-error-still-cleans-up
  (journal/with-stream support/verify-fatal))

(deftest ^{:stratum 0} acknowledged-start-allows-phase-entry
  (journal/with-stream support/verify-accepted))

(deftest ^{:stratum 0} explicitly-skipped-start-does-not-demand-intent
  (journal/with-stream support/verify-skipped))

(deftest ^{:stratum 0} legacy-nil-and-skipped-start-retain-execution
  (doseq [stream [nil (events/create-event-stream {:sinks []})]
          skip? [false true]]
    (let [calls (atom [])
          opts {:workflow/intent nil :skip-lifecycle-events skip?}
          completed (support/execute stream opts calls)]
      (is (= :completed (:execution/status completed)))
      (is (= [:phase-enter :cleanup] @calls)))))
