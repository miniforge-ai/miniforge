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

(ns ai.miniforge.workflow.loader-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.loader :as loader]))

;; Clear cache before each test
(use-fixtures :each
  (fn [f]
    (loader/clear-cache!)
    (f)))

(deftest load-from-resource-test
  (testing "Load workflow from resource"
    (let [workflow (loader/load-from-resource :canonical-sdlc-v1 "1.0.0")]
      (is (some? workflow) "Should load workflow from resource")
      (is (= :canonical-sdlc-v1 (:workflow/id workflow))
          "Should have correct workflow ID")
      (is (= "1.0.0" (:workflow/version workflow))
          "Should have correct version")
      (is (vector? (:workflow/phases workflow))
          "Should have phases vector")
      (is (seq (:workflow/phases workflow))
          "Should have at least one phase")))

  (testing "Load non-existent workflow returns nil"
    (let [workflow (loader/load-from-resource :non-existent-workflow "1.0.0")]
      (is (nil? workflow) "Should return nil for non-existent workflow")))

  (testing "Load simple test workflow"
    (let [workflow (loader/load-from-resource :simple-test-v1 "1.0.0")]
      (is (some? workflow) "Should load simple test workflow")
      (is (= :simple-test-v1 (:workflow/id workflow))
          "Should have correct workflow ID")
      (is (= 3 (count (:workflow/phases workflow)))
          "Should have 3 phases"))))

(deftest load-workflow-test
  (testing "Load workflow with validation"
    (let [result (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})]
      (is (some? result) "Should return result map")
      (is (= :resource (:source result))
          "First load should be from resource")
      (is (some? (:workflow result))
          "Should include workflow config")
      (is (true? (get-in result [:validation :valid?]))
          "Validation should pass")
      (is (empty? (get-in result [:validation :errors]))
          "Should have no validation errors")))

  (testing "Load workflow with caching"
    (loader/clear-cache!)  ; Clear cache for this test
    (let [result1 (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})
          result2 (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})]
      (is (= :resource (:source result1))
          "First load should be from resource")
      (is (= :cache (:source result2))
          "Second load should be from cache")
      (is (= (:workflow result1) (:workflow result2))
          "Cached workflow should be identical")))

  (testing "Load workflow with skip-cache option"
    (loader/clear-cache!)  ; Clear cache for this test
    (let [result1 (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})
          result2 (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {:skip-cache? true})]
      (is (= :resource (:source result1))
          "First load should be from resource")
      (is (= :resource (:source result2))
          "Load with skip-cache should bypass cache")))

  (testing "Load non-existent workflow throws exception"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Workflow not found"
                          (loader/load-workflow :non-existent "1.0.0" {}))
        "Should throw exception for non-existent workflow")))

(deftest load-workflow-validation-test
  (testing "Load workflow with skip-validation"
    (let [result (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {:skip-validation? true})]
      (is (some? result) "Should return result")
      (is (true? (get-in result [:validation :valid?]))
          "Should report valid when validation skipped")
      (is (empty? (get-in result [:validation :errors]))
          "Should have no errors when validation skipped"))))

(deftest list-available-workflows-test
  (testing "List available workflows from resources"
    (let [workflows (loader/list-available-workflows)]
      (is (vector? workflows) "Should return a vector")
      (is (seq workflows) "Should have at least one workflow")
      (is (every? #(contains? % :workflow/id) workflows)
          "All workflows should have IDs")
      (is (every? #(contains? % :workflow/version) workflows)
          "All workflows should have versions")
      (is (some #(= :canonical-sdlc-v1 (:workflow/id %)) workflows)
          "Should include canonical SDLC workflow")
      (is (some #(= :simple-test-v1 (:workflow/id %)) workflows)
          "Should include simple test workflow")))

  (testing "Workflow metadata includes expected fields"
    (let [workflows (loader/list-available-workflows)
          canonical (first (filter #(= :canonical-sdlc-v1 (:workflow/id %)) workflows))]
      (is (some? canonical) "Should find canonical workflow")
      (is (= "1.0.0" (:workflow/version canonical))
          "Should have version")
      (is (= :feature (:workflow/type canonical))
          "Should have type")
      (is (string? (:workflow/description canonical))
          "Should have description")
      (is (map? (:workflow/metadata canonical))
          "Should have metadata"))))

(deftest cache-management-test
  (testing "Clear cache removes all cached workflows"
    ;; Load and cache workflow
    (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})
    (let [result1 (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})]
      (is (= :cache (:source result1))
          "Should load from cache"))

    ;; Clear cache
    (loader/clear-cache!)

    ;; Load again - should be from resource
    (let [result2 (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})]
      (is (= :resource (:source result2))
          "Should load from resource after cache clear")))

  (testing "Different versions cached separately"
    (loader/clear-cache!)  ; Clear cache for this test
    ;; This is a conceptual test - in reality we only have one version
    ;; But the cache key includes version, so different versions would be cached separately
    (let [result1 (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})
          result2 (loader/load-workflow :simple-test-v1 "1.0.0" {})]
      (is (= :resource (:source result1))
          "First workflow should load from resource")
      (is (= :resource (:source result2))
          "Second workflow should load from resource")

      ;; Both should now be cached
      (let [result3 (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})
            result4 (loader/load-workflow :simple-test-v1 "1.0.0" {})]
        (is (= :cache (:source result3))
            "First workflow should load from cache")
        (is (= :cache (:source result4))
            "Second workflow should load from cache")))))
