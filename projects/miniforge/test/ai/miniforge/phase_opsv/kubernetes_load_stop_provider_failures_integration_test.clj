;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-provider-failures-integration-test
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-provider-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-unconfirmed [transform fixture]
  (let [response (transform (fixtures/response (:pod fixture)))
        [result calls] (fixtures/execute fixture response)]
    (is (= :unknown-outcome (:effect/state result)))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (nil? (:load/quiescence result)))
    (is (nil? (:recovery/verified? result)))))

(defn- ^{:stratum 0} check-callback [dispatch fixture]
  (let [pending (wire/completed (wire/response "{}"))
        [result calls] (fixtures/execute-response fixture pending dispatch)]
    (is (not= :succeeded (:effect/state result)))
    (is (empty? (fixtures/mutations calls)))))

(defn- ^{:stratum 0} check-once [dispatch fixture]
  (let [pending (wire/completed (wire/response
                                (json/generate-string (fixtures/response (:pod fixture)))))
        [result calls] (fixtures/execute-response fixture pending dispatch)]
    (is (= :succeeded (:effect/state result)))
    (is (= 1 (count (fixtures/mutations calls))))))

(defn- ^{:stratum 0} check-timeout [{:keys [runtime] :as fixture}]
  (let [pending (CompletableFuture.)
        [result calls] (fixtures/execute-response fixture pending fixtures/timeout-dispatch!)
        [retried retried-calls] (fixtures/execute fixture)]
    (is (= :unknown-outcome (:effect/state result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id (:recovery/record fixture)))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (.isCancelled pending))
    (is (not= :succeeded (:effect/state retried)))
    (is (empty? retried-calls))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} foreign-incomplete-or-unconfirmed-patch-receipts-never-prove-stop-or-recovery-test
  (doseq [transform [#(assoc-in % ["metadata" "uid"] "foreign-uid")
                     #(assoc-in % ["metadata" "name"] "other-name")
                     #(assoc-in % ["metadata" "namespace"] "other-namespace")
                     #(update % "metadata" dissoc "resourceVersion")
                     #(assoc-in % ["spec" "activeDeadlineSeconds"] 2)
                     #(assoc % "kind" "Job")]]
    (fixtures/with-proposed (partial check-unconfirmed transform))))

(deftest ^{:stratum 1} cross-thread-or-noninvoking-dispatch-cannot-trigger-a-patch-test
  (doseq [callback [nil dispatch/threaded! (constantly (dispatch/report :succeeded))]]
    (fixtures/with-proposed (partial check-callback callback))))

(deftest ^{:stratum 1} repeated-or-late-failing-dispatch-cannot-repeat-or-rewrite-the-confirmed-patch-test
  (doseq [callback [dispatch/twice! dispatch/after-result!]]
    (fixtures/with-proposed (partial check-once callback))))

(deftest ^{:stratum 1} timed-out-patch-cancels-its-owned-future-and-durable-uncertainty-prevents-a-retry-test
  (fixtures/with-proposed check-timeout))

(comment
  (fixtures/with-proposed check-timeout))
