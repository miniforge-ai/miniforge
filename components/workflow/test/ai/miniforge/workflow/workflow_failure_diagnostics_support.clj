;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.workflow-failure-diagnostics-support
  (:require [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.workflow-failure-test-support :as support]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} summarized-phase [summary]
  ;; leave-implement adds a summary but retains the response error and its data.
  {:name :implement :status :failed :error {:message summary}
   :result {:status :error :error {:message "Socket stalled"
                                  :data {:exception/class "java.net.SocketTimeoutException"}}}})

(defn- ^{:stratum 0} gate-phase []
  {:name :verify :status :failed :phase/status :failed
   :phase/gate-errors [{:code :reason/missing-artifact :message "Missing artifact"}]})

(defn- ^{:stratum 0} gate-message []
  (messages/t :phase/gate-failed-phase {:phase "verify"}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} summary-failure [summary]
  (assoc (support/context) :execution/status :failed :execution/current-phase :implement
         :phase (summarized-phase summary)))

(defn ^{:stratum 1} gate-failure []
  (-> (support/context)
      (assoc :execution/status :failed)
      (lifecycle/process-phase-result :verify (gate-phase) merge)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify-summary [stream options]
  (let [failed (summary-failure "Socket stalled")
        expected {:failure/class :failure.class/timeout :workflow/failure-reason "Socket stalled"}
        receipt (support/verify-context failed expected stream options)]
    (is (= "java.net.SocketTimeoutException"
           (get-in receipt [:workflow/error-details :diagnostic :data :exception/class])))))

(defn ^{:stratum 2} verify-gate [stream options]
  (let [failed (gate-failure)
        cause (:anomaly-map (response/last-entry (:execution/response-chain failed)))
        message (gate-message)
        expected {:failure/class :failure.class/policy
                  :workflow/anomaly-code :anomalies.gate/validation-failed
                  :workflow/failure-reason message}
        receipt (support/verify-context failed expected stream options)]
    (is (= cause (get-in receipt [:workflow/error-details :anomaly])))))
