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

(ns ai.miniforge.evidence-bundle.hash-test
  "Tests for SHA-256 content hashing."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.interface :as evidence]))

;------------------------------------------------------------------------------ Layer 0
;; Content Hash Tests

(deftest content-hash-deterministic-test
  (testing "Returns consistent hash for same input"
    (let [content {:type :terraform-plan :body "resource aws_instance"}
          hash-1 (evidence/content-hash content)
          hash-2 (evidence/content-hash content)]
      (is (= hash-1 hash-2)))))

(deftest content-hash-uniqueness-test
  (testing "Returns different hash for different input"
    (let [content-a {:type :terraform-plan :body "resource aws_instance"}
          content-b {:type :terraform-plan :body "resource aws_s3_bucket"}
          hash-a (evidence/content-hash content-a)
          hash-b (evidence/content-hash content-b)]
      (is (not= hash-a hash-b)))))

(deftest content-hash-format-test
  (testing "Hash is a 64-char hex string (SHA-256)"
    (let [content {:foo "bar"}
          h (evidence/content-hash content)]
      (is (string? h))
      (is (= 64 (count h)))
      (is (re-matches #"[0-9a-f]{64}" h)))))

(deftest content-hash-string-input-test
  (testing "Handles string content"
    (let [h (evidence/content-hash "hello world")]
      (is (= 64 (count h)))
      (is (re-matches #"[0-9a-f]{64}" h)))))

(deftest content-hash-nil-input-test
  (testing "Handles nil content"
    (let [h (evidence/content-hash nil)]
      (is (= 64 (count h)))
      (is (re-matches #"[0-9a-f]{64}" h)))))
