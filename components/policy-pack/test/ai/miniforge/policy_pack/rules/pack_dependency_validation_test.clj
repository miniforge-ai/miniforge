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

(ns ai.miniforge.policy-pack.rules.pack-dependency-validation-test
  "Tests for pack dependency validation rule (N4 §2.4.2)."
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
  (testing "Simple circular dependency (A → B → A) is detected"
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
  (testing "Complex circular dependency (A → B → C → A) is detected"
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

;------------------------------------------------------------------------------ Tests: Version Conflicts

(deftest test-version-constraint-satisfied
  (testing "Version constraint is satisfied"
    (let [pack-dep {:pack/id "shared-dep"
                    :pack/version "2026.01.25"
                    :pack/name "Shared Dep"
                    :pack/description "Test"
                    :pack/author "test"
                    :pack/categories []
                    :pack/rules []
                    :pack/created-at (java.time.Instant/now)
                    :pack/updated-at (java.time.Instant/now)}
          pack-1 {:pack/id "pack-1"
                  :pack/version "2026.01.25"
                  :pack/name "Pack 1"
                  :pack/description "Test"
                  :pack/author "test"
                  :pack/extends [{:pack-id "shared-dep"
                                 :version-constraint ">=2026.01.20"}]
                  :pack/categories []
                  :pack/rules []
                  :pack/created-at (java.time.Instant/now)
                  :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-pack-dependencies [pack-dep pack-1])]

      (is (:valid? result))
      (is (empty? (:violations result))))))

(deftest test-version-constraint-conflict
  (testing "Version constraint conflict is detected"
    (let [pack-dep {:pack/id "shared-dep"
                    :pack/version "2026.01.25"
                    :pack/name "Shared Dep"
                    :pack/description "Test"
                    :pack/author "test"
                    :pack/categories []
                    :pack/rules []
                    :pack/created-at (java.time.Instant/now)
                    :pack/updated-at (java.time.Instant/now)}
          pack-1 {:pack/id "pack-1"
                  :pack/version "2026.01.25"
                  :pack/name "Pack 1"
                  :pack/description "Test"
                  :pack/author "test"
                  :pack/extends [{:pack-id "shared-dep"
                                 :version-constraint ">=2026.01.01"}]
                  :pack/categories []
                  :pack/rules []
                  :pack/created-at (java.time.Instant/now)
                  :pack/updated-at (java.time.Instant/now)}
          pack-2 {:pack/id "pack-2"
                  :pack/version "2026.01.25"
                  :pack/name "Pack 2"
                  :pack/description "Test"
                  :pack/author "test"
                  :pack/extends [{:pack-id "shared-dep"
                                 :version-constraint "<2026.01.20"}]
                  :pack/categories []
                  :pack/rules []
                  :pack/created-at (java.time.Instant/now)
                  :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-pack-dependencies [pack-dep pack-1 pack-2])]

      (is (not (:valid? result)))
      (is (seq (:violations result)))
      (let [version-violation (first (filter #(= :version-conflict (:type %))
                                            (:violations result)))]
        (is version-violation)
        (is (= "shared-dep" (:dependency version-violation)))
        (is (= "2026.01.25" (:actual-version version-violation)))))))

(deftest test-wildcard-version-constraint
  (testing "Wildcard version constraint is satisfied"
    (let [pack-dep {:pack/id "dep"
                    :pack/version "2026.01.25"
                    :pack/name "Dep"
                    :pack/description "Test"
                    :pack/author "test"
                    :pack/categories []
                    :pack/rules []
                    :pack/created-at (java.time.Instant/now)
                    :pack/updated-at (java.time.Instant/now)}
          pack {:pack/id "pack"
                :pack/version "2026.01.25"
                :pack/name "Pack"
                :pack/description "Test"
                :pack/author "test"
                :pack/extends [{:pack-id "dep"
                               :version-constraint "2026.01.*"}]
                :pack/categories []
                :pack/rules []
                :pack/created-at (java.time.Instant/now)
                :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-pack-dependencies [pack-dep pack])]

      (is (:valid? result))
      (is (empty? (:violations result))))))

;------------------------------------------------------------------------------ Tests: Depth Limits

(deftest test-depth-limit-not-exceeded
  (testing "Dependency depth within limit passes validation"
    (let [depth-1 {:pack/id "depth-1"
                   :pack/version "1.0.0"
                   :pack/name "Depth 1"
                   :pack/description "Test"
                   :pack/author "test"
                   :pack/extends []
                   :pack/categories []
                   :pack/rules []
                   :pack/created-at (java.time.Instant/now)
                   :pack/updated-at (java.time.Instant/now)}
          depth-2 {:pack/id "depth-2"
                   :pack/version "1.0.0"
                   :pack/name "Depth 2"
                   :pack/description "Test"
                   :pack/author "test"
                   :pack/extends [{:pack-id "depth-1"}]
                   :pack/categories []
                   :pack/rules []
                   :pack/created-at (java.time.Instant/now)
                   :pack/updated-at (java.time.Instant/now)}
          depth-3 {:pack/id "depth-3"
                   :pack/version "1.0.0"
                   :pack/name "Depth 3"
                   :pack/description "Test"
                   :pack/author "test"
                   :pack/extends [{:pack-id "depth-2"}]
                   :pack/categories []
                   :pack/rules []
                   :pack/created-at (java.time.Instant/now)
                   :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-pack-dependencies
                  [depth-1 depth-2 depth-3]
                  {:max-depth 5})]

      (is (:valid? result))
      (is (empty? (:violations result)))
      (is (empty? (:warnings result))))))

(deftest test-depth-limit-exceeded
  (testing "Dependency depth exceeding limit triggers warning"
    (let [depth-1 {:pack/id "depth-1"
                   :pack/version "1.0.0"
                   :pack/name "Depth 1"
                   :pack/description "Test"
                   :pack/author "test"
                   :pack/extends []
                   :pack/categories []
                   :pack/rules []
                   :pack/created-at (java.time.Instant/now)
                   :pack/updated-at (java.time.Instant/now)}
          depth-2 {:pack/id "depth-2"
                   :pack/version "1.0.0"
                   :pack/name "Depth 2"
                   :pack/description "Test"
                   :pack/author "test"
                   :pack/extends [{:pack-id "depth-1"}]
                   :pack/categories []
                   :pack/rules []
                   :pack/created-at (java.time.Instant/now)
                   :pack/updated-at (java.time.Instant/now)}
          depth-3 {:pack/id "depth-3"
                   :pack/version "1.0.0"
                   :pack/name "Depth 3"
                   :pack/description "Test"
                   :pack/author "test"
                   :pack/extends [{:pack-id "depth-2"}]
                   :pack/categories []
                   :pack/rules []
                   :pack/created-at (java.time.Instant/now)
                   :pack/updated-at (java.time.Instant/now)}
          depth-4 {:pack/id "depth-4"
                   :pack/version "1.0.0"
                   :pack/name "Depth 4"
                   :pack/description "Test"
                   :pack/author "test"
                   :pack/extends [{:pack-id "depth-3"}]
                   :pack/categories []
                   :pack/rules []
                   :pack/created-at (java.time.Instant/now)
                   :pack/updated-at (java.time.Instant/now)}
          depth-5 {:pack/id "depth-5"
                   :pack/version "1.0.0"
                   :pack/name "Depth 5"
                   :pack/description "Test"
                   :pack/author "test"
                   :pack/extends [{:pack-id "depth-4"}]
                   :pack/categories []
                   :pack/rules []
                   :pack/created-at (java.time.Instant/now)
                   :pack/updated-at (java.time.Instant/now)}
          depth-6 {:pack/id "depth-6"
                   :pack/version "1.0.0"
                   :pack/name "Depth 6"
                   :pack/description "Test"
                   :pack/author "test"
                   :pack/extends [{:pack-id "depth-5"}]
                   :pack/categories []
                   :pack/rules []
                   :pack/created-at (java.time.Instant/now)
                   :pack/updated-at (java.time.Instant/now)}
          result (sut/validate-pack-dependencies
                  [depth-1 depth-2 depth-3 depth-4 depth-5 depth-6]
                  {:max-depth 5})]

      (is (not (:valid? result)))
      (is (seq (:warnings result)))
      (let [depth-warning (first (filter #(= :depth-limit (:type %))
                                        (:warnings result)))]
        (is depth-warning)
        (is (= "depth-6" (:pack-id depth-warning)))
        (is (= 6 (:depth depth-warning)))))))

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

;------------------------------------------------------------------------------ Tests: Version Parsing

(deftest test-version-parsing
  (testing "Version parsing handles DateVer format"
    (let [parse-version #'sut/parse-version]
      (is (= {:year 2026 :month 1 :day 25 :patch 0}
             (parse-version "2026.01.25")))
      (is (= {:year 2026 :month 1 :day 25 :patch 2}
             (parse-version "2026.01.25.2")))
      (is (nil? (parse-version "invalid"))))))

(deftest test-version-comparison
  (testing "Version comparison works correctly"
    (let [compare-versions #'sut/compare-versions]
      (is (neg? (compare-versions "2026.01.24" "2026.01.25")))
      (is (pos? (compare-versions "2026.01.26" "2026.01.25")))
      (is (zero? (compare-versions "2026.01.25" "2026.01.25")))
      (is (neg? (compare-versions "2026.01.25.1" "2026.01.25.2"))))))

(deftest test-version-constraints
  (testing "Version constraint satisfaction"
    (let [satisfies-constraint? #'sut/satisfies-constraint?]
      (is (satisfies-constraint? "2026.01.25" "2026.01.25"))
      (is (satisfies-constraint? "2026.01.25" ">=2026.01.20"))
      (is (satisfies-constraint? "2026.01.25" ">2026.01.20"))
      (is (satisfies-constraint? "2026.01.25" "<=2026.01.30"))
      (is (satisfies-constraint? "2026.01.25" "<2026.01.30"))
      (is (satisfies-constraint? "2026.01.25" "2026.01.*"))
      (is (not (satisfies-constraint? "2026.01.25" "<2026.01.20")))
      (is (not (satisfies-constraint? "2026.01.25" ">2026.01.30"))))))
