;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-journal-ownership-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-journal-test-support :as support]
            [ai.miniforge.event-stream.boundary.journal-files :as files]
            [ai.miniforge.event-stream.journal-storage :as storage]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} close-notifying [entered store]
  (deliver entered true)
  (storage/close! store))

(defn- ^{:stratum 0} check-unsafe-paths [directory]
  (let [lock (.toPath (io/file directory files/lock-filename))
        target (io/file directory "untouched")]
    (spit target "original")
    (Files/createSymbolicLink lock (.toPath target) (make-array FileAttribute 0))
    (is (anomaly/anomaly? (storage/open! directory)))
    (is (= "original" (slurp target)))))

(defn- ^{:stratum 0} check-interrupted [directory]
  (try
    (.interrupt (Thread/currentThread))
    (let [result (storage/open! directory)
          interrupted? (Thread/interrupted)]
      (is interrupted?)
      (is (anomaly/anomaly? result))
      (is (empty? (seq (.listFiles (io/file directory))))))
    (finally (Thread/interrupted))))

(defn- ^{:stratum 0} check-critical-recovery [directory]
  (let [error (Error. "injected")]
    (with-redefs [artifact/list-published (fn [_] (throw error))]
      (is (identical? error (try (storage/open! directory) (catch Error e e)))))
    (let [store (storage/open! directory)]
      (is (not (anomaly/anomaly? store)))
      (storage/close! store))))

(defn- ^{:stratum 0} check-process-ownership [directory]
  (let [store (storage/open! directory)]
    (try
      (is (= "false\n" (support/child-lock directory)))
      (is (anomaly/anomaly? (storage/open! directory)))
      (is (= "false\n" (support/child-lock directory)))
      (finally (storage/close! store)))
    (is (= "true\n" (support/child-lock directory)))
    (let [reopened (storage/open! directory)]
      (try
        (storage/close! store)
        (is (= "false\n" (support/child-lock directory)))
        (finally (storage/close! reopened))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unsafe-paths-and-pre-interrupted-open-are-refused
  (doseq [path [nil "" "."]] (is (anomaly/anomaly? (storage/open! path))))
  (support/with-directory check-unsafe-paths)
  (support/with-directory check-interrupted)
  (support/with-directory check-critical-recovery))

(defn- ^{:stratum 1} check-close-during-write [directory]
  (let [store (storage/open! directory)
        entered (promise)
        resume (promise)
        close-entered (promise)
        publish! artifact/publish!]
    (try
      (with-redefs [artifact/publish! (partial support/block-publication entered resume publish!)]
        (let [writing (future (storage/commit! store [:workflow (random-uuid)] (support/draft)))]
          (is (= true (deref entered support/timeout-ms :timeout)))
          (let [closing (future (close-notifying close-entered store))]
            (is (= true (deref close-entered support/timeout-ms :timeout)))
            (is (not (realized? closing)))
            (is (anomaly/anomaly? (storage/open! directory)))
            (deliver resume true)
            (is (= 0 (:event/sequence-number (deref writing support/timeout-ms nil))))
            (is (nil? (deref closing support/timeout-ms :timeout))))))
      (finally (deliver resume true) (storage/close! store)))))

(deftest ^{:stratum 1} process-lock-survives-duplicate-open-and-stale-close
  (support/with-directory check-process-ownership))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} close-waits-for-in-flight-acknowledgment
  (support/with-directory check-close-during-write))

(comment
  ::journal-lifecycle)
