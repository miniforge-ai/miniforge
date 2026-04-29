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

(ns ai.miniforge.content-hash.interface.sha256-output-test
  "Validate the shape of content-hash output: 64-char lowercase hex."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.content-hash.interface :as ch]))

(def ^:private hex-64-pattern #"[0-9a-f]{64}")

(deftest hash-is-string
  (testing "content-hash returns a string"
    (is (string? (ch/content-hash {:a 1})))))

(deftest hash-is-64-chars
  (testing "content-hash returns a 64-char string"
    (is (= 64 (count (ch/content-hash {:a 1}))))
    (is (= 64 (count (ch/content-hash "hello"))))
    (is (= 64 (count (ch/content-hash nil))))
    (is (= 64 (count (ch/content-hash [1 2 3]))))))

(deftest hash-is-lowercase-hex
  (testing "content-hash output matches lowercase hex pattern"
    (is (re-matches hex-64-pattern (ch/content-hash {:a 1})))
    (is (re-matches hex-64-pattern (ch/content-hash "hello world")))
    (is (re-matches hex-64-pattern (ch/content-hash nil)))
    (is (re-matches hex-64-pattern (ch/content-hash [:a :b :c])))))
