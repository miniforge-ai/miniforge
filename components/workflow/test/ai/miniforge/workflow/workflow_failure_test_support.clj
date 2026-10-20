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

(defn ^{:stratum 0} context []
  {:execution/id (random-uuid) :execution/status :running
   :execution/current-phase :verify :execution/errors []
   :execution/response-chain (response/create :failure-facts)})

(defn- ^{:stratum 0} throw-timeout [_ctx]
  (throw (SocketTimeoutException. "Socket stalled")))

(defn ^{:stratum 0} verify-context [failed expected stream options]
  (let [receipt (publishers/publish-workflow-completed! stream failed)
        history (events/get-events stream)]
    (is (= :failed (:execution/status failed)))
    (is (= [:workflow/failed] (mapv :event/type history)))
    (is (= receipt (first history)))
    (is (= (get expected :workflow/failure-phase (:execution/current-phase failed))
           (:workflow/failure-phase receipt)))
    (is (= expected (select-keys receipt (keys expected))))
    (is (= (:execution/errors failed) (get-in receipt [:workflow/error-details :errors])))
    (events/close-current-event-stream! stream)
    (is (= history (support/reopened-events options)))
    receipt))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failed-context []
  (lifecycle/execute-enter {:config {:phase :verify} :enter throw-timeout} (context)))

(defn- ^{:stratum 1} native-review-failure []
  (let [message "Socket stalled"
        cause (response/make-anomaly :anomalies.review/backend-timeout message)]
    (assoc (context) :execution/status :failed :execution/current-phase :review
           :phase {:name :review :error cause :result {:error {:message message}}})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify-failure [stream options]
  (verify-context (failed-context) {:failure/class :failure.class/timeout
                                    :workflow/failure-reason "Socket stalled"} stream options))

(defn ^{:stratum 2} verify-native-failure [stream options]
  (let [failed (native-review-failure)
        expected {:workflow/anomaly-code :anomalies.review/backend-timeout
                  :workflow/failure-reason "Socket stalled"}
        receipt (verify-context failed expected stream options)]
    (is (= (get-in failed [:phase :error]) (get-in receipt [:workflow/error-details :anomaly])))))
