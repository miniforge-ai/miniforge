;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.retained-preparation-failures-test
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.retained-preparation-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.retained-preparation-test-support :as storage]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} throw-failure [failure & _arguments] (throw failure))

(defn- ^{:stratum 0} check-uncached-read [{:keys [ctx directory request id value]}]
  (is (= (get-in value [:artifact/content :comparison/preparation])
         (phase/read-retained-verification-preparation! ctx request id)))
  (storage/withdraw! directory id)
  (is (= :unavailable (:anomaly/type (phase/read-retained-verification-preparation! ctx request id)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-failures [{:keys [ctx request id]}]
  (let [read! #(phase/read-retained-verification-preparation! ctx request id)]
    (with-redefs [artifact/read-published (partial throw-failure (java.io.IOException.))]
      (let [result (read!)]
        (is (= :unavailable (:anomaly/type result)))
        (is (= {} (:anomaly/data result)))))
    (with-redefs [artifact/read-published (partial throw-failure (AssertionError.))]
      (is (thrown? AssertionError (read!))))
    (let [interrupted (InterruptedException.)]
      (with-redefs [artifact/read-published (partial throw-failure interrupted)]
        (try+
          (read!)
          (is false "interruption must propagate")
          (catch InterruptedException caught
            (let [interrupted? (.isInterrupted (Thread/currentThread))]
              (is (identical? interrupted caught))
              (is interrupted?)))
          (finally (Thread/interrupted)))))))

(deftest ^{:stratum 1} publication-is-re-read-without-a-success-cache-test
  (fixtures/with-retained check-uncached-read))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} storage-failures-are-safe-while-fatal-and-interruption-propagate-test
  (fixtures/with-retained check-failures))
