;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.retained-preparation-storage-integration-test
  (:require [ai.miniforge.artifact.publication-files :as files]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.retained-preparation-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} throw-failure [failure _file] (throw failure))

(defn- ^{:stratum 0} counted-read [calls original file]
  (swap! calls inc)
  (original file))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-storage-errors [{:keys [ctx request id]}]
  (doseq [[failure expected] [[(java.io.IOException.) :unavailable] [(AssertionError.) :fatal]]]
    (with-redefs [files/read-bytes (partial throw-failure failure)]
      (let [result (phase/read-retained-verification-preparation! ctx request id)]
        (is (= expected (:anomaly/type result)))
        (is (= {} (:anomaly/data result)))))))

(defn- ^{:stratum 1} check-storage-interruption [{:keys [ctx request id]}]
  (with-redefs [files/read-bytes (partial throw-failure (InterruptedException.))]
    (try+
      (phase/read-retained-verification-preparation! ctx request id)
      (is false "lower-level interruption must propagate")
      (catch InterruptedException _
        (is (.isInterrupted (Thread/currentThread))))
      (finally (Thread/interrupted)))))

(defn- ^{:stratum 1} check-preinterrupted [{:keys [ctx request id]}]
  (let [calls (atom 0)
        original files/read-bytes]
    (with-redefs [files/read-bytes (partial counted-read calls original)]
      (try+
        (.interrupt (Thread/currentThread))
        (phase/read-retained-verification-preparation! ctx request id)
        (is false "cancelled readers must refuse before file I/O")
        (catch InterruptedException _
          (is (.isInterrupted (Thread/currentThread))))
        (finally (Thread/interrupted))))
    (is (zero? @calls))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} real-publication-read-preserves-fatal-classification-and-safe-failure-test
  (fixtures/with-retained check-storage-errors))

(deftest ^{:stratum 2} real-publication-read-interruption-propagates-with-its-flag-test
  (fixtures/with-retained check-storage-interruption))

(deftest ^{:stratum 2} already-interrupted-reader-does-not-read-the-file-test
  (fixtures/with-retained check-preinterrupted))
