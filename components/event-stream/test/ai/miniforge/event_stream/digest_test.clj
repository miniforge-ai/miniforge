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
   [ai.miniforge.event-stream.digest :as digest]))

;------------------------------------------------------------------------------ happy path

(deftest returns-required-keys
  (testing "digest-content always returns all three keys"
    (let [result (digest/digest-content "hello")]
      (is (contains? result :digest/preview))
      (is (contains? result :digest/sha256))
      (is (contains? result :digest/original-size)))))

(deftest known-sha256-for-empty-string
  (testing "SHA-256 of empty string matches well-known value"
    ;; echo -n "" | sha256sum = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
    (let [{:keys [:digest/sha256]} (digest/digest-content "")]
      (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
             sha256)))))

(deftest known-sha256-for-hello
  (testing "SHA-256 of 'hello' matches well-known value"
    ;; echo -n "hello" | sha256sum = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
    (let [{:keys [:digest/sha256]} (digest/digest-content "hello")]
      (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
             sha256)))))

(deftest original-size-matches-utf8-byte-length
  (testing "original-size is the byte count, not char count"
    (let [s      "héllo"                ; 'é' is 2 bytes in UTF-8
          result (digest/digest-content s)
          ba     (.getBytes ^String s "UTF-8")]
      (is (= (alength ba) (:digest/original-size result))))))

(deftest preview-capped-at-1024-chars
  (testing "preview is at most 1024 characters regardless of input length"
    (let [big    (apply str (repeat 2000 "x"))
          result (digest/digest-content big)]
      (is (= 1024 (count (:digest/preview result)))))))

(deftest preview-is-exact-for-short-strings
  (testing "preview equals the full string when shorter than 1024"
    (let [s      "short content"
          result (digest/digest-content s)]
      (is (= s (:digest/preview result))))))

;------------------------------------------------------------------------------ edge cases

(deftest accepts-byte-array
  (testing "byte arrays are digested without conversion error"
    (let [ba     (.getBytes "hello" "UTF-8")
          result (digest/digest-content ba)]
      (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
             (:digest/sha256 result)))
      (is (= 5 (:digest/original-size result))))))

(deftest accepts-map-via-str-coercion
  (testing "non-string non-bytes values are str-coerced before hashing"
    (let [m      {:tool/name "Read" :file "/foo.clj"}
          result (digest/digest-content m)]
      (is (string? (:digest/preview result)))
      (is (= 64 (count (:digest/sha256 result))))
      (is (pos? (:digest/original-size result))))))

(deftest sha256-is-lowercase-hex
  (testing "SHA-256 output is exactly 64 lowercase hex chars"
    (doseq [content ["" "hello" "the quick brown fox"]]
      (let [{:keys [:digest/sha256]} (digest/digest-content content)]
        (is (= 64 (count sha256))
            (str "wrong length for: " content))
        (is (re-matches #"^[0-9a-f]{64}$" sha256)
            (str "not lowercase hex for: " content))))))

(deftest deterministic-for-same-input
  (testing "two calls with equal input produce equal digests"
    (let [content "deterministic test string"
          r1      (digest/digest-content content)
          r2      (digest/digest-content content)]
      (is (= r1 r2)))))
