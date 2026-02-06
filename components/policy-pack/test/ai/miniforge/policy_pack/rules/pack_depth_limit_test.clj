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

(ns ai.miniforge.policy-pack.rules.pack-depth-limit-test
  "Tests for pack dependency depth limit validation."
  (:require
   [ai.miniforge.policy-pack.rules.pack-dependency-validation :as sut]
   [clojure.test :refer [deftest is testing]]))

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
