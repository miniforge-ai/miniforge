;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.policy-pack.crypto-test
  "Tests for Ed25519 public-key decoding and verification guards."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.crypto :as sut]))

;------------------------------------------------------------------------------ Layer 0

;; ============================================================================
;; Ed25519 key decoding
;; ============================================================================
(deftest ^{:stratum 0} raw-ed25519-point-test
  (testing "the sign of x comes from the top bit of the final byte"
    (let [even-key (byte-array (concat (repeat 31 (byte 0)) [(unchecked-byte 0x7f)]))
          odd-key  (byte-array (concat (repeat 31 (byte 0)) [(unchecked-byte 0xff)]))]
      (is (false? (first (sut/raw-ed25519-point even-key))))
      (is (true? (first (sut/raw-ed25519-point odd-key))))

      (testing "and that bit is cleared from y rather than left in it"
        (is (= (second (sut/raw-ed25519-point even-key))
               (second (sut/raw-ed25519-point odd-key)))))))

  (testing "y is read little-endian"
    ;; 0x01 in the FIRST byte is the least significant byte of y.
    (let [key-bytes (byte-array (concat [(byte 1)] (repeat 31 (byte 0))))]
      (is (= (biginteger 1) (second (sut/raw-ed25519-point key-bytes))))))

  (testing "the caller's array is not mutated"
    (let [key-bytes (byte-array (concat (repeat 31 (byte 0)) [(unchecked-byte 0xff)]))]
      (sut/raw-ed25519-point key-bytes)
      (is (= (unchecked-byte 0xff) (aget key-bytes 31))))))

(deftest ^{:stratum 0} verify-ed25519-key-length-test
  (let [content (.getBytes "payload" "UTF-8")
        sig     (byte-array 64)]
    (testing "a nil public key fails closed"
      (is (not (:verified? (sut/verify-ed25519 content sig nil)))))

    (testing "a public key of the wrong length fails closed"
      (is (not (:verified? (sut/verify-ed25519 content sig (byte-array 16))))))

    (testing "an exception with no message still yields a reason"
      ;; .getMessage is nil for some exceptions, and a nil reason is the one
      ;; thing this fn promises never to return.
      (let [result (with-redefs [sut/raw-ed25519-point
                                 (fn [_] (throw (NullPointerException.)))]
                     (sut/verify-ed25519 content sig (byte-array 32)))]
        (is (not (:verified? result)))
        (is (string? (:reason result)))))))
