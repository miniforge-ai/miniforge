;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.evidence-reference-support
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.runner-events :as publishers]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-terminal [completed id stream options]
  (publishers/publish-workflow-completed! stream completed)
  (let [history (events/get-events stream)
        event (first history)]
    (is (= [:workflow/completed] (mapv :event/type history)))
    (is (= id (:workflow/evidence-bundle-id event)))
    (is (= (:execution/id completed) (:workflow/id event)))
    (is (nat-int? (:workflow/duration-ms event)))
    (events/close-current-event-stream! stream)
    (is (= history (journal/reopened-events options)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} assert-completed [completed snapshot id]
  (is (= id (:workflow/evidence-bundle-id completed) (:workflow/evidence-bundle-id snapshot)))
  (journal/with-stream (partial verify-terminal completed id)))
