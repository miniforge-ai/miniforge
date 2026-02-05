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

(ns ai.miniforge.policy-pack.rules.pack-version-constraint-test
  "Tests for version constraints and version parsing (N4 S2.4.2)."
  (:require
   [ai.miniforge.policy-pack.rules.pack-dependency-validation :as sut]
   [clojure.test :refer [deftest is testing]]))

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
