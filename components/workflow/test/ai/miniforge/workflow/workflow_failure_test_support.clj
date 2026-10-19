;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.workflow-failure-test-support
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [ai.miniforge.workflow.phase-event-test-support :as support]
            [ai.miniforge.workflow.runner-events :as publishers]
            [clojure.test :refer [is]])
  (:import [java.net SocketTimeoutException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} context []
  {:execution/id (random-uuid) :execution/status :running
   :execution/current-phase :verify :execution/errors []
   :execution/response-chain (response/create :failure-facts)})

(defn- ^{:stratum 0} throw-timeout [_ctx]
  (throw (SocketTimeoutException. "Socket stalled")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failed-context []
  (lifecycle/execute-enter {:config {:phase :verify} :enter throw-timeout} (context)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify-failure [stream options]
  (let [failed (failed-context)
        receipt (publishers/publish-workflow-completed! stream failed)
        history (events/get-events stream)]
    (is (= :failed (:execution/status failed)))
    (is (= [:workflow/failed] (mapv :event/type history)))
    (is (= receipt (first history)))
    (is (= :verify (:workflow/failure-phase receipt)))
    (is (= "Socket stalled" (:workflow/failure-reason receipt)))
    (is (= :failure.class/timeout (:failure/class receipt)))
    (is (= (:execution/errors failed) (get-in receipt [:workflow/error-details :errors])))
    (events/close-current-event-stream! stream)
    (is (= history (support/reopened-events options)))))
