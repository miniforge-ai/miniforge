;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.policy-pack.rules.pack-dependency-graph-test
  "Tests for pack dependency graph validation: no issues, circular, and missing deps."
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

(def valid-pack-c
  "Pack that depends on pack-b (transitive to pack-a)."
  {:pack/id "pack-c"
   :pack/version "2026.01.25"
   :pack/name "Pack C"
   :pack/description "Test pack C"
   :pack/author "test"
   :pack/extends [{:pack-id "pack-b"}]
   :pack/categories []
   :pack/rules []
   :pack/created-at (java.time.Instant/now)
   :pack/updated-at (java.time.Instant/now)})

;------------------------------------------------------------------------------ Tests: No Issues

(deftest test-no-dependencies
  (testing "Pack with no dependencies passes validation"
    (let [result (sut/validate-pack-dependencies [valid-pack-a])]
      (is (:valid? result))
      (is (empty? (:violations result)))
      (is (empty? (:warnings result))))))

(deftest test-valid-linear-dependencies
  (testing "Valid linear dependency chain passes validation"
    (let [result (sut/validate-pack-dependencies
                  [valid-pack-a valid-pack-b valid-pack-c])]
      (is (:valid? result))
      (is (empty? (:violations result)))
      (is (empty? (:warnings result))))))

;------------------------------------------------------------------------------ Tests: Circular Dependencies

(deftest test-circular-dependency-simple
  (testing "Simple circular dependency (A -> B -> A) is detected"
    (let [pack-a {:pack/id "pack-a"
                  :pack/version "2026.01.25"
                  :pack/name "Pack A"
                  :pack/description "Test"
                  :pack/author "test"
                  :pack/extends [{:pack-id "pack-b"}]
                  :pack/categories []
                  :pack/rules []
                  :pack/created-at (java.time.Instant/now)
                  :pack/updated-at (java.time.Instant/now)}
          pack-b {:pack/id "pack-b"
                  :pack/version "2026.01.25"
                  :pack/name "Pack B"
                  :pack/description "Test"
                  :pack/author "test"
                  :pack/extends [{:pack-id "pack-a"}]
                  :pack/categories []
                  :pack/rules []
                  :pack/created-at (java.time.Instant/now)
                  :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-pack-dependencies [pack-a pack-b])]

      (is (not (:valid? result)))
      (is (seq (:violations result)))
      (is (some #(= :circular-dependency (:type %)) (:violations result))))))

(deftest test-circular-dependency-complex
  (testing "Complex circular dependency (A -> B -> C -> A) is detected"
    (let [pack-a {:pack/id "pack-a"
                  :pack/version "2026.01.25"
                  :pack/name "Pack A"
                  :pack/description "Test"
                  :pack/author "test"
                  :pack/extends [{:pack-id "pack-b"}]
                  :pack/categories []
                  :pack/rules []
                  :pack/created-at (java.time.Instant/now)
                  :pack/updated-at (java.time.Instant/now)}
          pack-b {:pack/id "pack-b"
                  :pack/version "2026.01.25"
                  :pack/name "Pack B"
                  :pack/description "Test"
                  :pack/author "test"
                  :pack/extends [{:pack-id "pack-c"}]
                  :pack/categories []
                  :pack/rules []
                  :pack/created-at (java.time.Instant/now)
                  :pack/updated-at (java.time.Instant/now)}
          pack-c {:pack/id "pack-c"
                  :pack/version "2026.01.25"
                  :pack/name "Pack C"
                  :pack/description "Test"
                  :pack/author "test"
                  :pack/extends [{:pack-id "pack-a"}]
                  :pack/categories []
                  :pack/rules []
                  :pack/created-at (java.time.Instant/now)
                  :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-pack-dependencies [pack-a pack-b pack-c])]

      (is (not (:valid? result)))
      (is (seq (:violations result)))
      (let [circular-violation (first (filter #(= :circular-dependency (:type %))
                                              (:violations result)))]
        (is circular-violation)
        (is (vector? (:cycle circular-violation)))
        (is (>= (count (:cycle circular-violation)) 3))))))

;------------------------------------------------------------------------------ Tests: Missing Dependencies

(deftest test-missing-dependency
  (testing "Missing dependency is detected"
    (let [pack-with-missing {:pack/id "pack-x"
                             :pack/version "2026.01.25"
                             :pack/name "Pack X"
                             :pack/description "Test"
                             :pack/author "test"
                             :pack/extends [{:pack-id "nonexistent-pack"}]
                             :pack/categories []
                             :pack/rules []
                             :pack/created-at (java.time.Instant/now)
                             :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-pack-dependencies [pack-with-missing])]

      (is (not (:valid? result)))
      (is (seq (:violations result)))
      (let [missing-violation (first (filter #(= :missing-dependency (:type %))
                                            (:violations result)))]
        (is missing-violation)
        (is (= "pack-x" (:pack-id missing-violation)))
        (is (= "nonexistent-pack" (:missing-dep missing-violation)))))))

(deftest test-multiple-missing-dependencies
  (testing "Multiple missing dependencies are all detected"
    (let [pack {:pack/id "pack-x"
                :pack/version "2026.01.25"
                :pack/name "Pack X"
                :pack/description "Test"
                :pack/author "test"
                :pack/extends [{:pack-id "missing-1"}
                              {:pack-id "missing-2"}
                              {:pack-id "missing-3"}]
                :pack/categories []
                :pack/rules []
                :pack/created-at (java.time.Instant/now)
                :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-pack-dependencies [pack])
          missing-violations (filter #(= :missing-dependency (:type %))
                                    (:violations result))]

      (is (not (:valid? result)))
      (is (= 3 (count missing-violations)))
      (is (every? #(= "pack-x" (:pack-id %)) missing-violations)))))
