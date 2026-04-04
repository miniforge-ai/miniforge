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

(ns ai.miniforge.config.digest-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.config.digest :as digest]))

(deftest sha256-hex-test
  (testing "produces consistent hex digest with sha256: prefix"
    (let [result (digest/sha256-hex "hello")]
      (is (string? result))
      (is (.startsWith result "sha256:"))
      (is (= 71 (count result)))  ; "sha256:" (7) + 64 hex chars
      ;; Known SHA-256 of "hello"
      (is (= "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
             result))))

  (testing "different inputs produce different digests"
    (is (not= (digest/sha256-hex "hello")
              (digest/sha256-hex "world"))))

  (testing "same input produces same digest"
    (is (= (digest/sha256-hex "test content")
           (digest/sha256-hex "test content"))))

  (testing "empty string has a valid digest"
    (let [result (digest/sha256-hex "")]
      (is (.startsWith result "sha256:")))))

(deftest load-digest-manifest-test
  (testing "loads digest manifest from classpath"
    (let [manifest (digest/load-digest-manifest)]
      (is (map? manifest))
      (is (contains? manifest :readiness))
      (is (contains? manifest :risk))
      (is (contains? manifest :tiers))
      (is (contains? manifest :knowledge-safety))
      (is (every? #(.startsWith % "sha256:") (vals manifest))))))

(deftest verify-governance-file-test
  (testing "returns :ok for matching content"
    (let [manifest (digest/load-digest-manifest)
          content (slurp (io/resource "config/governance/readiness.edn"))]
      (when (and manifest (:readiness manifest))
        (is (= :ok (digest/verify-governance-file :readiness content))))))

  (testing "returns :mismatch for tampered content"
    (let [manifest (digest/load-digest-manifest)]
      (when manifest
        (is (= :mismatch (digest/verify-governance-file :readiness "tampered content"))))))

  (testing "returns :no-entry for unknown key"
    (let [manifest (digest/load-digest-manifest)]
      (when manifest
        (is (= :no-entry (digest/verify-governance-file :nonexistent "anything")))))))
