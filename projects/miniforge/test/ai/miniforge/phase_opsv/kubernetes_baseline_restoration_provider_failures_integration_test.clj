;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration-provider-failures-integration-test
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-provider-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-fixtures :as replica]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-unconfirmed [transform status fixture]
  (let [response (transform (fixtures/response fixture))
        pending (wire/completed (wire/response (json/generate-string response) {:status status}))
        [result calls] (fixtures/execute-response fixture pending dispatch/dispatch!)]
    (is (= :unknown-outcome (:effect/state result)))
    (is (= 1 (count (fixtures/mutations calls))))))

(defn- ^{:stratum 0} check-dispatch [callback expected? count-mutations fixture]
  (let [pending (wire/completed (wire/response (json/generate-string (fixtures/response fixture))))
        [result calls] (fixtures/execute-response fixture pending callback)]
    (is (= expected? (= :succeeded (:effect/state result))))
    (is (= count-mutations (count (fixtures/mutations calls))))))

(defn- ^{:stratum 0} check-timeout [{:keys [runtime] :as fixture}]
  (let [pending (CompletableFuture.)
        operation (partial fixtures/execute-response fixture pending dispatch/dispatch!)
        [result calls] (replica/with-preparation-budget 1 operation)
        [retried retry-calls] (fixtures/execute fixture)]
    (is (= :unknown-outcome (:effect/state result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id (:recovery/record fixture)))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (.isCancelled pending))
    (is (not= :succeeded (:effect/state retried)))
    (is (empty? retry-calls))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} foreign-or-unconfirmed-native-restoration-receipts-remain-uncertain-test
  (doseq [transform [#(assoc-in % ["metadata" "uid"] "foreign-deployment")
                     #(assoc-in % ["metadata" "name"] "foreign-name")
                     #(assoc-in % ["metadata" "namespace"] "foreign-namespace")
                     #(update % "metadata" dissoc "resourceVersion")
                     #(assoc % "kind" "StatefulSet")
                     #(dissoc % "apiVersion")
                     #(assoc-in % ["spec" "replicas"] 3)
                     (constantly {"kind" "Status"})]]
    (fixtures/with-proposed (partial check-unconfirmed transform transport/success-status))))

(deftest ^{:stratum 1} only-native-patch-success-status-is-accepted-test
  (doseq [status [transport/created-status transport/accepted-status transport/not-found-status]]
    (fixtures/with-proposed (partial check-unconfirmed identity status))))

(deftest ^{:stratum 1} callbacks-cannot-repeat-fabricate-or-defer-restoration-test
  (doseq [[callback expected? mutations] [[dispatch/twice! true 1]
                                          [dispatch/after-result! true 1]
                                          [nil false 0]
                                          [(constantly (dispatch/report :succeeded)) false 0]]]
    (fixtures/with-proposed (partial check-dispatch callback expected? mutations))))

(deftest ^{:stratum 1} restoration-cannot-be-dispatched-from-another-thread-test
  (fixtures/with-proposed (partial check-dispatch dispatch/threaded! false 0)))

(deftest ^{:stratum 1} uncertain-restoration-cancels-the-owned-future-without-retrying-test
  (fixtures/with-proposed check-timeout))

(comment
  (fixtures/with-proposed check-timeout))
