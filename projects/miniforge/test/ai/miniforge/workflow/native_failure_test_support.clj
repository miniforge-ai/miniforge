;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.native-failure-test-support
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-deployment.interface]
            [ai.miniforge.phase-software-factory.interface]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [ai.miniforge.workflow.workflow-failure-test-support :as support]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} throw-timeout [_ctx]
  (throw (java.net.SocketTimeoutException. "Socket stalled")))

(defn- ^{:stratum 0} gated-context []
  (let [result (phase/success nil "Verified" {:tokens 0 :duration-ms 0})
        phase-result {:name :verify :status :completed :phase/status :failed
                      :phase/gate-errors [{:code :reason/missing-artifact :message "Missing artifact"}]
                      :result result}]
    (assoc (support/context) :phase phase-result)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} deployment-failure [phase-name]
  (let [interceptor (assoc (phase/get-phase-interceptor {:phase phase-name}) :enter throw-timeout)
        ctx (assoc (support/context) :execution/current-phase phase-name)]
    (assoc (lifecycle/execute-enter interceptor ctx) :execution/status :failed)))

(defn ^{:stratum 1} verification-gate-failure []
  (let [leave (:leave (phase/get-phase-interceptor {:phase :verify}))
        ctx (leave (gated-context))]
    (-> ctx
        (assoc :execution/status :failed)
        (lifecycle/process-phase-result :verify (:phase ctx) merge))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify-deployment [phase-name stream options]
  (let [failed (deployment-failure phase-name)
        records (:execution/errors failed)
        leave-failed (lifecycle/execute-leave {:name phase-name :leave throw-timeout} (support/context))
        expected {:failure/class :failure.class/timeout :workflow/failure-reason "Socket stalled"}
        receipt (support/verify-context failed expected stream options)]
    (is (= 2 (count records)))
    (is (= [phase-name phase-name] (mapv :phase records)))
    (is (= phase-name (:phase (last (:execution/errors leave-failed)))))
    (is (= :phase-error (:type (first records))))
    (is (= (:anomaly (first records)) (:anomaly (last records))))
    (is (= records (get-in receipt [:workflow/error-details :errors])))))

(defn ^{:stratum 2} verify-native-gate [stream options]
  (let [failed (verification-gate-failure)
        summary (get-in failed [:phase :error])
        cause (:anomaly-map (response/last-entry (:execution/response-chain failed)))
        message (:message summary)
        expected {:failure/class :failure.class/policy
                  :workflow/anomaly-code :anomalies.gate/validation-failed
                  :workflow/failure-reason message}
        receipt (support/verify-context failed expected stream options)]
    (is (true? (:gate-failed? summary)))
    (is (= :success (:agent-status summary)))
    (is (= cause (get-in receipt [:workflow/error-details :anomaly])))))
