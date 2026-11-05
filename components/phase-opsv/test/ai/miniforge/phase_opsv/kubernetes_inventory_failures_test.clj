;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-inventory-failures-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as failures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} one-deadline-covers-both-roots-and-both-inventories-test
  (let [deadline io/deadline
        calls (atom 0)
        [result] (with-redefs [io/deadline (fn [millis] (swap! calls inc) (deadline millis))]
                   (fixtures/capture! (fixtures/routes) {}))]
    (is (not (anomaly/anomaly? result)))
    (is (= 1 @calls)))
  (with-redefs [io/remaining-ms (constantly 0)]
    (let [[result calls] (fixtures/capture! (fixtures/routes) {})]
      (is (= :timeout (:anomaly/type result)))
      (is (empty? calls)))))

(deftest ^{:stratum 0} unavailable-fatal-and-cancellation-preserve-their-native-meaning-test
  (let [[result] (fixtures/capture! (assoc (fixtures/routes) fixtures/replica-sets-path (java.io.IOException.)) {})]
    (is (= :unavailable (:anomaly/type result))))
  (doseq [failure [(AssertionError.) (InterruptedException.)]]
    (let [routes (assoc (fixtures/routes) fixtures/pods-path failure)
          operation (partial fixtures/capture! routes {})
          [thrown flagged?] (failures/interrupted-result operation)]
      (is (identical? failure thrown))
      (is (= (instance? InterruptedException failure) flagged?)))))

(deftest ^{:stratum 0} no-request-path-is-derived-from-an-owner-reference-test
  (let [routes (assoc-in (fixtures/routes)
                         [fixtures/pods-path "items" 0 "metadata" "ownerReferences" 0 "kind"] "Secret")
        [result calls] (fixtures/capture! routes {})]
    (is (not (anomaly/anomaly? result)))
    (is (= [fixtures/namespace-path fixtures/deployment-path fixtures/replica-sets-path fixtures/pods-path
             fixtures/namespace-path fixtures/deployment-path]
           (mapv wire/path calls)))))

(deftest ^{:stratum 0} unsupported-list-types-and-missing-required-roots-fail-closed-test
  (doseq [routes [(dissoc (fixtures/routes) fixtures/namespace-path)
                  (dissoc (fixtures/routes) fixtures/deployment-path)
                  (assoc-in (fixtures/routes) [fixtures/replica-sets-path "kind"] "StatefulSetList")
                  (assoc-in (fixtures/routes) [fixtures/pods-path "items" 0 "kind"] "Node")]]
    (let [[result] (fixtures/capture! routes {})]
      (is (anomaly/anomaly? result)))))

(deftest ^{:stratum 0} root-recreation-during-inventory-cannot-return-a-bound-observation-test
  (let [reply wire/reply!]
    (doseq [target-path [fixtures/namespace-path fixtures/deployment-path]]
      (let [seen (atom {})
            [result] (with-redefs [wire/reply!
                                  (fn [routes calls options]
                                    (let [path (wire/path options)
                                          count-at-path (get (swap! seen update path (fnil inc 0)) path)
                                          routes (if (and (= target-path path) (> count-at-path 1))
                                                   (assoc-in routes [path "metadata" "uid"] "recreated-during-read")
                                                   routes)]
                                      (reply routes calls options)))]
                       (fixtures/capture! (fixtures/routes) {}))]
        (is (= :conflict (:anomaly/type result)))))))

(comment ::shared-read-boundary-never-promotes-an-observation-to-authority)
