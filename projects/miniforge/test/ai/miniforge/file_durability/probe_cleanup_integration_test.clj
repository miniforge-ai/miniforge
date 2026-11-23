;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.probe-cleanup-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.probe-cleanup-fixtures :as fixtures]
            [ai.miniforge.file-durability.probe-resource-fixtures :as ownership]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-session! [parent outcome child handles]
  (let [observed (volatile! nil)
        operation (partial ownership/register! observed child outcome)
        delete! (partial ownership/delete-after-death! handles @ownership/delete-scratch)
        actual (with-redefs-fn {ownership/delete-scratch delete!}
                 #(ownership/capture! parent fixtures/budget-ms operation))]
    (if (instance? Throwable outcome)
      (is (identical? outcome (:thrown actual)))
      (is (= {:returned outcome} actual)))
    (is (every? fixtures/dead? handles))
    (is (not (fs/exists? @observed)))))

(defn- ^{:stratum 0} verify-retained! [parent child _handles]
  (let [observed (volatile! nil)
        operation (partial ownership/register! observed child :complete)
        actual (with-redefs-fn {ownership/signal-native (constantly nil)}
                 #(ownership/capture! parent fixtures/refusal-ms operation))
        failure (:object actual)]
    (is (anomaly/anomaly? failure))
    (is (= :unavailable (:anomaly/type failure)))
    (is (= :unresolved (get-in failure [:anomaly/data :durability/cleanup])))
    (is (= (str @observed) (get-in failure [:anomaly/data :durability/path])))
    (is (.isAlive ^Process (:proc child)))
    (is (fs/exists? @observed))))

(defn- ^{:stratum 0} verify-fatal-retained! [parent child _handles]
  (let [observed (volatile! nil)
        failure (Error.)
        operation (partial ownership/register! observed child failure)
        actual (with-redefs-fn {ownership/signal-native (constantly nil)}
                 #(ownership/capture! parent fixtures/refusal-ms operation))]
    (is (identical? failure (:thrown actual)))
    (is (= 1 (count (.getSuppressed failure))))
    (is (.isAlive ^Process (:proc child)))
    (is (fs/exists? @observed))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} with-parent-session! [outcome parent]
  (doseq [mode (keys fixtures/scripts)]
    (fixtures/with-native! mode (partial verify-session! parent outcome))))

(defn- ^{:stratum 1} with-retained! [parent]
  (fixtures/with-native! :single (partial verify-retained! parent)))

(defn- ^{:stratum 1} with-fatal-retained! [parent]
  (fixtures/with-native! :single (partial verify-fatal-retained! parent)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-directory-deletion-observes-process-death-not-a-later-grace-period
  (support/with-root (partial with-parent-session! :complete)))

(deftest ^{:stratum 2 :integration true} test-failed-operation-still-reaps-before-cleaning-scratch
  (support/with-root (partial with-parent-session! (java.io.IOException.))))

(deftest ^{:stratum 2 :integration true} test-fatal-operation-still-reaps-without-masking-the-error
  (support/with-root (partial with-parent-session! (Error.))))

(deftest ^{:stratum 2 :integration true} test-unconfirmed-termination-retains-private-scratch-and-explicit-failure
  (support/with-root with-retained!))

(deftest ^{:stratum 2 :integration true} test-unconfirmed-cleanup-does-not-mask-fatal-operation
  (support/with-root with-fatal-retained!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.probe-cleanup-integration-test))
