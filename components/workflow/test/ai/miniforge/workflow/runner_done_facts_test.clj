;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-done-facts-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.phase-event-test-support :as support]
            [ai.miniforge.workflow.phase-test-support :as fixture]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each fixture/with-workflow-phase-test-support)

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workflow []
  {:workflow/id :terminal-facts :workflow/version "1.0.0"
   :workflow/pipeline [{:phase :done}]})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-completion [stream options]
  (let [completed (support/run-phase-workflow stream (workflow))
        phase (get-in completed [:execution/phase-results :done])
        history (events/get-events stream)
        [entry receipt] history]
    (is (= :completed (:execution/status completed)))
    (is (empty? (:execution/errors completed)))
    (is (= [:workflow/phase-started :workflow/phase-completed] (mapv :event/type history)))
    (is (= [:done :done] (mapv :workflow/phase history)))
    (is (= :agent/none (:phase/expected-agent entry)))
    (is (= :success (:phase/outcome receipt)))
    (is (= [] (:phase/artifacts receipt) (get-in phase [:result :artifacts])))
    (is (nat-int? (:phase/duration-ms receipt)))
    (is (= (:duration-ms phase) (:phase/duration-ms receipt)))
    (is (= (:metrics phase) (get-in phase [:result :metrics])))
    (is (= 0 (get-in completed [:execution/metrics :tokens])))
    (events/close-current-event-stream! stream)
    (is (= history (support/reopened-events options)))))

(deftest ^{:stratum 1} terminal-phase-retains-legacy-and-observer-only-execution
  (doseq [stream [nil (events/create-event-stream {:sinks []})]]
    (let [completed (support/run-phase-workflow stream (workflow))]
      (is (= :completed (:execution/status completed)))
      (is (= [] (get-in completed [:execution/phase-results :done :result :artifacts]))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} actual-terminal-phase-completes-through-current-journal
  (support/with-stream verify-completion))
