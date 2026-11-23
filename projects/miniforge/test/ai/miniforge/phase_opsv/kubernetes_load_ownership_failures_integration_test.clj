;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-ownership-failures-integration-test
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} read! [{:keys [runtime plan]}]
  (phase/read-kubernetes-load-ownership! runtime plan))

(defn- ^{:stratum 0} throw-failure [failure & _] (throw failure))

(defn- ^{:stratum 0} check-input [{:keys [runtime plan] :as fixture}]
  (doseq [[selected-runtime selected-plan]
          [[nil plan] [(dissoc runtime :effects-directory) plan]
           [(assoc runtime :grant/allowed? true) plan] [runtime {}]
           [runtime (assoc plan :load/uid "caller-selected")]]]
    (let [[result calls] (fixtures/observe (assoc fixture :runtime selected-runtime :plan selected-plan))]
      (is (= :invalid-input (:anomaly/type result)))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-absence [fixture]
  (let [pending (wire/completed (wire/response "" {:status 404}))
        [result calls] (fixtures/observe-response fixture pending)]
    (is (= :conflict (:anomaly/type result)))
    (is (not (contains? result :load/pod)))
    (is (= 1 (count calls)))))

(defn- ^{:stratum 0} check-timeout [fixture]
  (let [pending (CompletableFuture.)
        [result calls] (with-redefs [io/remaining-ms (constantly 1)]
                         (fixtures/observe-response fixture pending))]
    (is (= :unavailable (:anomaly/type result)))
    (is (= 1 (count calls)))
    (is (.isCancelled pending))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-read-failures [fixture]
  (with-redefs [effect/read-record (partial throw-failure (java.io.IOException.))]
    (is (= :unavailable (:anomaly/type (read! fixture)))))
  (with-redefs [effect/read-record (partial throw-failure (AssertionError.))]
    (is (thrown? AssertionError (read! fixture))))
  (let [interrupted (InterruptedException.)]
    (try
      (with-redefs [effect/read-record (partial throw-failure interrupted)]
        (read! fixture)
        (is false "interruption must propagate"))
      (catch InterruptedException caught
        (let [flagged? (.isInterrupted (Thread/currentThread))]
          (is (identical? interrupted caught))
          (is flagged?)))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 1} invalid-input-and-caller-approval-fields-cannot-start-provider-reads-test
  (fixtures/with-created check-input))

(deftest ^{:stratum 1} pod-absence-is-not-ownership-or-container-termination-proof-test
  (fixtures/with-created check-absence))

(deftest ^{:stratum 1} timeout-cancels-the-owned-http-future-without-inventing-observation-test
  (fixtures/with-created check-timeout))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} native-ownership-read-preserves-fatal-and-interruption-semantics-test
  (fixtures/with-created check-read-failures))

(comment ::faults-after-real-creation-no-live-cluster-or-timing-sensitive-success-assertions)
