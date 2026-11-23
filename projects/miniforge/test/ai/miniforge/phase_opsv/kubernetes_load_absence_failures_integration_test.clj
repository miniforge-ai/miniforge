;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-absence-failures-integration-test
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.kubernetes-load-absence-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as pending]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-pending [fixture]
  (let [future (CompletableFuture.)
        respond! (fn [calls options] (swap! calls conj options) future)
        [result calls] (with-redefs [io/remaining-ms (constantly 1)]
                         (fixtures/observe-with fixture respond!))]
    (is (= :unresolved (:load/resource-state result)))
    (is (= 1 (count calls)))
    (is (.isCancelled future))))

(defn- ^{:stratum 0} check-interruption [fixture]
  (let [interrupted (InterruptedException.)
        respond! (fn [calls options] (swap! calls conj options) (pending/failed-future interrupted))
        [caught flagged?] (pending/interrupted-result #(fixtures/observe-with fixture respond!))]
    (is (identical? interrupted caught))
    (is flagged?)))

(defn- ^{:stratum 0} check-deadline [fixture]
  (let [deadline io/deadline
        observed (atom [])
        traced (fn [millis] (let [until (deadline millis)] (swap! observed conj until) until))
        [result calls] (with-redefs [io/deadline traced]
                         (fixtures/observe fixture (get-in fixture [:native :routes])))]
    (is (= :absent (:load/resource-state result)))
    (is (= 5 (count calls)))
    (is (= 1 (count @observed)))))

(defn- ^{:stratum 0} check-fatal [fixture]
  (let [failure (AssertionError. "fatal owned-record failure")]
    (with-redefs [effect/read-record (fn [& _] (throw failure))]
      (is (thrown? AssertionError (fixtures/observe fixture (get-in fixture [:native :routes])))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} timeout-cancels-owned-read-without-retry-or-absence-proof-test
  (fixtures/with-created check-pending))

(deftest ^{:stratum 1} interruption-propagates-and-preserves-the-thread-flag-test
  (fixtures/with-created check-interruption))

(deftest ^{:stratum 1} all-five-native-reads-share-one-original-deadline-test
  (fixtures/with-created check-deadline))

(deftest ^{:stratum 1} fatal-record-failures-are-not-converted-to-ordinary-observation-test
  (fixtures/with-created check-fatal))

(comment
  (fixtures/with-created check-pending))
