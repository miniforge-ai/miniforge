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
(ns ai.miniforge.utf8.interface-test
  "decode-strict accepts exactly the input the JDK's REPORT decoder
   accepts, and returns the same text for it."
  (:require
   [ai.miniforge.utf8.interface :as utf8]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio ByteBuffer]
   [java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets]
   [java.util Random]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private sample-seed 2375)

(def ^{:stratum 0} ^:private sample-count 5000)

(def ^{:stratum 0} ^:private sample-max-length 8)

(defn- ^{:stratum 0} utf8-bytes [^String text]
  (.getBytes text StandardCharsets/UTF_8))

(defn- ^{:stratum 0} octets [& values]
  (byte-array (map unchecked-byte values)))

(defn- ^{:stratum 0} jdk-strict
  "The JVM's own strict decode: the reference decode-strict must match."
  [^bytes bytes]
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap bytes)))
    (catch CharacterCodingException _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} random-bytes [^Random random]
  (let [bytes (byte-array (.nextInt random (inc sample-max-length)))]
    (.nextBytes random bytes)
    bytes))

(deftest ^{:stratum 1} well-formed-text-decodes-unchanged-test
  (testing "given well-formed UTF-8 → the same text"
    (doseq [text ["" "plain ascii" "héllo" "€ and 𝄞" "﻿bom kept" "� literal"]]
      (is (= text (utf8/decode-strict (utf8-bytes text)))))))

(deftest ^{:stratum 1} malformed-input-is-rejected-test
  (testing "given malformed UTF-8 → nil, never U+FFFD"
    (doseq [[label bytes] {"lone continuation"   (octets 0x80)
                           "invalid lead byte"   (octets 0xFF 0x41)
                           "truncated sequence"  (octets 0x41 0xEF 0xBF)
                           "overlong slash"      (octets 0xC0 0xAF)
                           "encoded surrogate"   (octets 0xED 0xA0 0x80)
                           "past U+10FFFF"       (octets 0xF4 0x90 0x80 0x80)
                           "replacement + stray" (octets 0xEF 0xBF 0xBD 0x80)}]
      (is (nil? (utf8/decode-strict bytes)) label))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} agrees-with-the-jdk-report-decoder-test
  (testing "given random short byte strings → the JDK strict decoder's answer"
    (let [random (Random. sample-seed)]
      (doseq [bytes (repeatedly sample-count #(random-bytes random))]
        (is (= (jdk-strict bytes) (utf8/decode-strict bytes))
            (pr-str (vec bytes)))))))

;;------------------------------------------------------------------------------ Rich Comment
(comment
  (jdk-strict (octets 0xC0 0xAF))

  :leave-this-here)
