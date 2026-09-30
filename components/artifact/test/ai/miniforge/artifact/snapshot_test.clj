;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.snapshot-test
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.artifact.messages :as msg]
            [ai.miniforge.artifact.publication-codec :as codec]
            [ai.miniforge.artifact.publication-record :as record]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} value []
  (artifact/build-artifact
   {:id (random-uuid) :type :manifest :version "1.0.0"
    :content {:date #inst "2026-09-29" :instant (Instant/parse "2026-09-29T00:00:00.123456789Z")
              :message "verified" :list (list 1 2) :vector [1 2] :set #{:a :b}}}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} lossless-checkpoint-snapshot-test
  (let [expected (value)
        encoded (artifact/encode-snapshot expected)]
    (is (string? encoded))
    (is (= expected (artifact/decode-snapshot encoded)))
    (is (seq? (get-in (artifact/decode-snapshot encoded) [:artifact/content :list])))
    (is (vector? (get-in (artifact/decode-snapshot encoded) [:artifact/content :vector])))))

(deftest ^{:stratum 1} snapshots-fail-closed-test
  (let [encoded (artifact/encode-snapshot (value))]
    (doseq [invalid [nil 42 {} "" "{}" (str encoded "{}")
                     (str/replace encoded "verified" "modified")]]
      (is (= :invalid-input (:anomaly/type (artifact/decode-snapshot invalid))))))
  (doseq [invalid [nil 42 {}]]
    (let [result (artifact/encode-snapshot invalid)]
      (is (= :invalid-input (:anomaly/type result)))
      (is (= (msg/t :snapshot/invalid) (:anomaly/message result)))))
  (let [invalid (String. ^bytes (record/encode {}) StandardCharsets/UTF_8)]
    (is (= :invalid-input (:anomaly/type (artifact/decode-snapshot invalid))))))

(deftest ^{:stratum 1} snapshots-enforce-byte-budget-test
  (let [expected (value) encoded (artifact/encode-snapshot expected)]
    (with-redefs [codec/maximum-bytes 8]
      (is (= :invalid-input (:anomaly/type (artifact/encode-snapshot expected))))
      (is (= :invalid-input (:anomaly/type (artifact/decode-snapshot encoded))))
      (is (= :invalid-input (:anomaly/type (artifact/decode-snapshot "界界界")))))))

(deftest ^{:stratum 1} snapshot-boundary-preserves-fatal-and-interruption-test
  (with-redefs [record/decode (fn [_] (throw (AssertionError.)))]
    (is (= :fatal (:anomaly/type (artifact/decode-snapshot "{}")))))
  (let [[result interrupted?]
        (with-redefs [record/encode (fn [_] (throw (InterruptedException.)))]
          (let [result (artifact/encode-snapshot (value))]
            [result (Thread/interrupted)]))]
    (is (= :invalid-input (:anomaly/type result)))
    (is interrupted?)))
