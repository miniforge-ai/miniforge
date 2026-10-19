;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.workflow-failure-publication-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.runner-events :as publishers]
            [ai.miniforge.workflow.workflow-failure-test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} legacy-failure-events-retain-the-actual-reason-and-error-records
  (let [stream (events/create-event-stream {:sinks []})
        failed (support/failed-context)]
    (publishers/publish-workflow-completed! stream failed)
    (let [event (first (events/get-events stream))]
      (is (= :workflow/failed (:event/type event)))
      (is (= "Socket stalled" (:workflow/failure-reason event)))
      (is (= :failure.class/timeout (:failure/class event)))
      (is (= (:execution/errors failed) (get-in event [:workflow/error-details :errors]))))))

(deftest ^{:stratum 0} normalized-enter-exception-reaches-a-durable-failure-event
  (journal/with-stream support/verify-failure))
