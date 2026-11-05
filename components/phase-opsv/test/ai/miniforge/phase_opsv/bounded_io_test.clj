;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.bounded-io-test
  (:require [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.prometheus-test-support :as fixture]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [clojure.test :refer [deftest is]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeoutException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private test-timeout-ms 10000)

(def ^{:stratum 0} ^:private read-limit 3)

(def ^{:stratum 0} ^:private malformed-utf8 (byte-array [(unchecked-byte 0xc3) 0x28]))

(defn- ^{:stratum 0} record-release! [calls] (swap! calls inc))

(defn- ^{:stratum 0} record-reader! [started _task] (reset! started true))

(defn- ^{:stratum 0} expect-timeout! [operation]
  (is (thrown? TimeoutException (operation))))

(defn- ^{:stratum 0} body [text]
  (ByteArrayInputStream. (.getBytes ^String text StandardCharsets/UTF_8)))

(deftest ^{:stratum 0} test-expired-await-preserves-already-completed-critical-failures
  (doseq [complete [fixture/failed-future fixture/failed-task]
          failure [(AssertionError.) (InterruptedException.)]]
    (let [pending (complete failure)
          operation (partial io/await! pending 0)]
      (with-redefs [io/remaining-ms (constantly 0)]
        (let [[thrown _flagged?] (fixture/interrupted-result operation)]
          (is (identical? failure (cleanup/fatal-cause thrown))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} text-decoding-never-replaces-invalid-utf8-silently
  (is (= "μ" (io/utf8-text (.getBytes "μ" StandardCharsets/UTF_8))))
  (is (thrown? IllegalArgumentException (io/utf8-text malformed-utf8))))

(deftest ^{:stratum 1} response-reading-stops-after-the-limit-sentinel-byte
  (with-open [stream (body "abcdef")]
    (let [bytes (io/read-bytes! stream read-limit (io/deadline test-timeout-ms))]
      (is (= "abcd" (io/utf8-text bytes))))))

(deftest ^{:stratum 1} expired-deadlines-never-return-late-data
  (with-open [stream (body "abc")]
    (let [started (atom false)
          start! (partial record-reader! started)
          operation (partial io/read-bytes! stream read-limit (io/deadline 0))]
      (with-redefs-fn {#'io/start-reader! start!} (partial expect-timeout! operation))
      (is (false? @started)))))

(deftest ^{:stratum 1} owned-cleanup-preserves-a-returned-value
  (let [calls (atom 0)]
    (is (= ::observed (io/owned-result! (constantly ::observed) (partial record-release! calls))))
    (is (= 1 @calls))))

(comment ::owned-read-port-acceptance)
