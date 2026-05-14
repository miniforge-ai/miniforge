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

(ns ai.miniforge.event-stream.digest-test
  "Tests for the content-digest utility."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.digest :as digest])
  (:import
   [java.nio.charset StandardCharsets]))

(def ^:private empty-content "")
(def ^:private hello-content "hello")
(def ^:private multibyte-content "héllo")
(def ^:private short-content "short content")
(def ^:private fox-content "the quick brown fox")
(def ^:private deterministic-content "deterministic test string")
(def ^:private two-byte-codepoint "é")
(def ^:private two-byte-codepoint-width 2)
(def ^:private emoji-codepoint "😀")
(def ^:private emoji-codepoint-width 4)
(def ^:private empty-sha256
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
(def ^:private hello-sha256
  "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
(def ^:private lowercase-hex-shape #"^[0-9a-f]+$")
(def ^:private sample-map {:tool/name "Read" :file "/foo.clj"})

(defn- utf8-size
  [^String s]
  (alength (.getBytes s StandardCharsets/UTF_8)))

;------------------------------------------------------------------------------ happy path

(deftest returns-required-keys
  (testing "digest-content always returns all three keys"
    (let [result (digest/digest-content hello-content)]
      (is (contains? result :digest/preview))
      (is (contains? result :digest/sha256))
      (is (contains? result :digest/original-size)))))

(deftest known-sha256-for-empty-string
  (testing "SHA-256 of empty string matches well-known value"
    (let [{:keys [:digest/sha256]} (digest/digest-content empty-content)]
      (is (= empty-sha256 sha256)))))

(deftest known-sha256-for-hello
  (testing "SHA-256 of 'hello' matches well-known value"
    (let [{:keys [:digest/sha256]} (digest/digest-content hello-content)]
      (is (= hello-sha256 sha256)))))

(deftest original-size-matches-utf8-byte-length
  (testing "original-size is the byte count, not char count"
    (let [result (digest/digest-content multibyte-content)]
      (is (= (utf8-size multibyte-content) (:digest/original-size result))))))

(deftest preview-capped-at-max-preview-bytes
  (testing "preview is at most max-preview-bytes regardless of input length"
    (let [big    (apply str (repeat (* 2 digest/max-preview-bytes) "x"))
          result (digest/digest-content big)]
      (is (= digest/max-preview-bytes (utf8-size (:digest/preview result)))))))

(deftest preview-capped-by-utf8-byte-length
  (testing "preview cap counts UTF-8 bytes instead of characters"
    (let [content (apply str (repeat digest/max-preview-bytes two-byte-codepoint))
          preview (:digest/preview (digest/digest-content content))]
      (is (<= (utf8-size preview) digest/max-preview-bytes))
      (is (= (quot digest/max-preview-bytes two-byte-codepoint-width)
             (count preview))))))

(deftest preview-keeps-code-points-intact
  (testing "preview truncation does not split surrogate pairs"
    (let [content     (apply str (repeat digest/max-preview-bytes emoji-codepoint))
          preview     (:digest/preview (digest/digest-content content))
          code-points (.codePointCount ^String preview 0 (.length ^String preview))]
      (is (<= (utf8-size preview) digest/max-preview-bytes))
      (is (= (quot digest/max-preview-bytes emoji-codepoint-width) code-points)))))

(deftest preview-is-exact-for-short-strings
  (testing "preview equals the full string when shorter than the byte cap"
    (let [result (digest/digest-content short-content)]
      (is (= short-content (:digest/preview result))))))

;------------------------------------------------------------------------------ edge cases

(deftest accepts-byte-array
  (testing "byte arrays are digested without conversion error"
    (let [ba     (.getBytes hello-content StandardCharsets/UTF_8)
          result (digest/digest-content ba)]
      (is (= hello-sha256 (:digest/sha256 result)))
      (is (= (utf8-size hello-content) (:digest/original-size result))))))

(deftest accepts-map-via-str-coercion
  (testing "non-string non-bytes values are str-coerced before hashing"
    (let [result (digest/digest-content sample-map)]
      (is (string? (:digest/preview result)))
      (is (= digest/sha256-hex-length (count (:digest/sha256 result))))
      (is (pos? (:digest/original-size result))))))

(deftest sha256-is-lowercase-hex
  (testing "SHA-256 output is exactly 64 lowercase hex chars"
    (doseq [content [empty-content hello-content fox-content]]
      (let [{:keys [:digest/sha256]} (digest/digest-content content)]
        (is (= digest/sha256-hex-length (count sha256))
            (str "wrong length for: " content))
        (is (re-matches lowercase-hex-shape sha256)
            (str "not lowercase hex for: " content))))))

(deftest deterministic-for-same-input
  (testing "two calls with equal input produce equal digests"
    (let [r1 (digest/digest-content deterministic-content)
          r2 (digest/digest-content deterministic-content)]
      (is (= r1 r2)))))
