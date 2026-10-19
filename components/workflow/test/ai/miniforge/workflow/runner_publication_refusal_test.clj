;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-publication-refusal-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.phase-event-test-support :as support]
            [ai.miniforge.workflow.phase-test-support :as fixture]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each fixture/with-workflow-phase-test-support)

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workflow [agent]
  {:workflow/id :runner-publication-refusal :workflow/version "1.0.0"
   :workflow/pipeline [{:phase fixture/runner-test-plan :agent agent}]})

(defn- ^{:stratum 0} assert-refusal [result boundary effects?]
  (let [error (last (:execution/errors result))]
    (is (= :failed (:execution/status result)))
    (is (= :phase-callback-failed (:type error)))
    (is (= boundary (:boundary error)))
    (is (anomaly/any-anomaly? (:anomaly error)))
    (is (= effects? (boolean (seq (:execution/phase-results result)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-closed-journal [stream _options]
  (events/close-current-event-stream! stream)
  (assert-refusal (support/run-phase-workflow stream (workflow :reviewer)) :on-phase-start false)
  (is (empty? (events/get-events stream))))

(defn- ^{:stratum 1} verify-invalid-agent [stream _options]
  (assert-refusal (support/run-phase-workflow stream (workflow false)) :on-phase-start false)
  (is (empty? (events/get-events stream))))

(defn- ^{:stratum 1} verify-missing-completion-facts [stream options]
  ;; The test phase has no authoritative artifact facts: current admission must refuse.
  (assert-refusal (support/run-phase-workflow stream (workflow :reviewer)) :on-phase-complete true)
  (let [history (events/get-events stream)]
    (is (= [:workflow/phase-started] (mapv :event/type history)))
    (is (= [:reviewer] (mapv :phase/expected-agent history)))
    (events/close-current-event-stream! stream)
    (is (= history (support/reopened-events options)))))

(deftest ^{:stratum 1} nil-stream-retains-observer-only-execution
  (let [result (support/run-phase-workflow nil (workflow :reviewer))]
    (is (= :completed (:execution/status result)))
    (is (seq (:execution/phase-results result)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} closed-journal-prevents-phase-effects
  (support/with-stream verify-closed-journal))

(deftest ^{:stratum 2} invalid-entry-facts-prevent-phase-effects
  (support/with-stream verify-invalid-agent))

(deftest ^{:stratum 2} refused-completion-does-not-report-workflow-success
  (support/with-stream verify-missing-completion-facts))
