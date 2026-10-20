;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.terminal-admission-test
  (:require [ai.miniforge.workflow.checkpoint-test-support :as checkpoints]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.phase-test-support :as fixture]
            [ai.miniforge.workflow.terminal-admission-test-support :as support]
            [clojure.test :refer [deftest use-fixtures]]))

(use-fixtures :each checkpoints/with-temp-checkpoint-root fixture/with-workflow-phase-test-support)

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} refused-completion-is-failed-in-returned-output-and-reopened-checkpoint
  (journal/with-stream support/verify-refusal))

(deftest ^{:stratum 0} acknowledged-completion-remains-successful
  (journal/with-stream support/verify-accepted))

(deftest ^{:stratum 0} terminal-constructor-failure-is-not-success
  (journal/with-stream support/verify-constructor-refusal))

(deftest ^{:stratum 0} fatal-terminal-errors-still-clean-up
  (journal/with-stream support/verify-fatal))

(deftest ^{:stratum 0} explicit-lifecycle-skip-retains-completion
  (journal/with-stream support/verify-skipped))

(comment ::terminal-runner-regressions)
