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

(ns ai.miniforge.policy-pack.rules.pack-validation-test
  "Tests for single pack validation and trust placeholders."
  (:require
   [ai.miniforge.policy-pack.rules.pack-dependency-validation :as sut]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Test Fixtures

(def valid-pack-a
  "Simple pack with no dependencies."
  {:pack/id "pack-a"
   :pack/version "2026.01.25"
   :pack/name "Pack A"
   :pack/description "Test pack A"
   :pack/author "test"
   :pack/categories []
   :pack/rules []
   :pack/created-at (java.time.Instant/now)
   :pack/updated-at (java.time.Instant/now)})

(def valid-pack-b
  "Pack that depends on pack-a."
  {:pack/id "pack-b"
   :pack/version "2026.01.25"
   :pack/name "Pack B"
   :pack/description "Test pack B"
   :pack/author "test"
   :pack/extends [{:pack-id "pack-a"}]
   :pack/categories []
   :pack/rules []
   :pack/created-at (java.time.Instant/now)
   :pack/updated-at (java.time.Instant/now)})

;------------------------------------------------------------------------------ Tests: Trust Constraints
;; Note: These are placeholder tests for when PR14 (Transitive Trust Rules) is merged

(deftest test-trust-validation-placeholder
  (testing "Trust validation is disabled by default (waiting for PR14)"
    (let [result (sut/validate-pack-dependencies
                  [valid-pack-a]
                  {:check-trust? false})]
      (is (:valid? result))
      ;; No trust violations should be detected yet
      (is (not-any? #(= :trust-violation (:type %)) (:violations result))))))

;------------------------------------------------------------------------------ Tests: Validate Single Pack

(deftest test-validate-single-pack
  (testing "Validate single pack against registry"
    (let [registry [valid-pack-a valid-pack-b]
          new-pack {:pack/id "pack-d"
                    :pack/version "2026.01.25"
                    :pack/name "Pack D"
                    :pack/description "Test"
                    :pack/author "test"
                    :pack/extends [{:pack-id "pack-a"}]
                    :pack/categories []
                    :pack/rules []
                    :pack/created-at (java.time.Instant/now)
                    :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-single-pack new-pack registry)]

      (is (:valid? result))
      (is (empty? (:violations result))))))

(deftest test-validate-single-pack-with-missing-dep
  (testing "Single pack validation catches missing dependency"
    (let [registry [valid-pack-a]
          new-pack {:pack/id "pack-d"
                    :pack/version "2026.01.25"
                    :pack/name "Pack D"
                    :pack/description "Test"
                    :pack/author "test"
                    :pack/extends [{:pack-id "nonexistent"}]
                    :pack/categories []
                    :pack/rules []
                    :pack/created-at (java.time.Instant/now)
                    :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-single-pack new-pack registry)]

      (is (not (:valid? result)))
      (is (seq (:violations result)))
      (is (some #(= :missing-dependency (:type %)) (:violations result))))))
