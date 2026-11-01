;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-io-test
  (:require [ai.miniforge.phase-opsv.boundary.prometheus-io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset CharacterCodingException StandardCharsets]
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

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} text-decoding-never-replaces-invalid-utf8-silently
  (is (= "μ" (io/utf8-text (.getBytes "μ" StandardCharsets/UTF_8))))
  (is (thrown? CharacterCodingException (io/utf8-text malformed-utf8))))

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
