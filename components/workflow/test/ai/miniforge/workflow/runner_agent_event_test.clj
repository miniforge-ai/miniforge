;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-agent-event-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.phase-event-test-support :as support]
            [ai.miniforge.workflow.phase-test-support :as fixture]
            [ai.miniforge.workflow.runner-events :as publishers]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each fixture/with-workflow-phase-test-support)

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workflow []
  {:workflow/id :runner-agent-facts :workflow/version "1.0.0"
   :workflow/pipeline [{:phase fixture/runner-test-plan :agent :configured-reviewer}]})

(defn- ^{:stratum 0} run-entry [stream definition]
  ;; Only entry publication is under test; other lifecycle facts are a separate migration.
  (with-redefs [publishers/publish-phase-completed! (constantly nil)]
    (support/run-phase-workflow stream definition)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-runner-entry [stream options]
  (let [result (run-entry stream (workflow))
        history (events/get-events stream)
        entry (first history)]
    (is (= :completed (:execution/status result)))
    (is (= 1 (count history)))
    (is (= :workflow/phase-started (:event/type entry)))
    (is (= fixture/runner-test-plan (:workflow/phase entry)))
    (is (= :configured-reviewer (:phase/expected-agent entry)))
    (is (= (:execution/id result) (:workflow/id entry)))
    (events/close-current-event-stream! stream)
    (is (= history (support/reopened-events options)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} active-runner-publishes-the-interceptors-agent
  (support/with-stream verify-runner-entry))
