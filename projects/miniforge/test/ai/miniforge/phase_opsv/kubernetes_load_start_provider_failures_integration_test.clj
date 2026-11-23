;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-provider-failures-integration-test
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-provider-fixtures :as owned]
            [ai.miniforge.phase-opsv.kubernetes-load-start-provider-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-unconfirmed [transform status fixture]
  (let [pod (transform (fixtures/response (:pod fixture)))
        pending (wire/completed (wire/response (json/generate-string pod) {:status status}))
        [result calls] (fixtures/execute-response fixture pending dispatch/dispatch!)]
    (is (= :unknown-outcome (:effect/state result)))
    (is (= 1 (count (fixtures/mutations calls))))))

(defn- ^{:stratum 0} check-dispatch [callback expected? count-mutations fixture]
  (let [response (fixtures/response (:pod fixture))
        pending (wire/completed (wire/response (json/generate-string response)))
        [result calls] (fixtures/execute-response fixture pending callback)]
    (is (= expected? (= :succeeded (:effect/state result))))
    (is (= count-mutations (count (fixtures/mutations calls))))))

(defn- ^{:stratum 0} check-timeout [{:keys [runtime] :as fixture}]
  (let [pending (CompletableFuture.)
        [result calls] (fixtures/execute-response fixture pending owned/timeout-dispatch!)
        [retried retry-calls] (fixtures/execute fixture)]
    (is (= :unknown-outcome (:effect/state result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id (:recovery/record fixture)))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (.isCancelled pending))
    (is (not= :succeeded (:effect/state retried)))
    (is (empty? retry-calls))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unproven-gate-release-and-foreign-or-incomplete-receipts-remain-uncertain-test
  (doseq [transform [#(assoc-in % ["metadata" "uid"] "foreign-worker")
                     #(assoc-in % ["metadata" "name"] "foreign-name")
                     #(assoc-in % ["metadata" "namespace"] "foreign-namespace")
                     #(update % "metadata" dissoc "resourceVersion")
                     #(dissoc % "spec")
                     #(assoc-in % ["spec" "schedulingGates"] [{"name" "unreleased-gate"}])
                     #(assoc-in % ["spec" "schedulingGates"] nil)]]
    (fixtures/with-proposed (partial check-unconfirmed transform transport/success-status))))

(deftest ^{:stratum 1} start-does-not-adopt-delete-or-create-success-statuses-test
  (doseq [status [transport/created-status transport/accepted-status transport/not-found-status]]
    (fixtures/with-proposed (partial check-unconfirmed identity status))))

(deftest ^{:stratum 1} callbacks-cannot-repeat-fabricate-or-defer-forward-gate-release-test
  (doseq [[callback expected? mutations] [[dispatch/twice! true 1]
                                          [dispatch/after-result! true 1]
                                          [nil false 0]
                                          [(constantly (dispatch/report :succeeded)) false 0]]]
    (fixtures/with-proposed (partial check-dispatch callback expected? mutations))))

(deftest ^{:stratum 1} forward-gate-release-cannot-be-dispatched-from-another-thread-test
  (fixtures/with-proposed (partial check-dispatch dispatch/threaded! false 0)))

(deftest ^{:stratum 1} uncertain-start-cancels-the-owned-future-and-never-retries-test
  (fixtures/with-proposed check-timeout))

(comment
  (fixtures/with-proposed check-timeout))
