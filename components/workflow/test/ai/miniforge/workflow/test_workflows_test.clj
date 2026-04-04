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

(ns ai.miniforge.workflow.test-workflows-test
  "Tests for test workflow configurations (simple-test-v1, minimal-test-v1)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [clojure.edn :as edn]))

;; ============================================================================
;; Workflow loading tests
;; ============================================================================

(defn load-workflow-edn
  "Load a workflow EDN file from resources."
  [workflow-id version]
  (let [filename (str "workflows/" (name workflow-id) "-v" version ".edn")
        resource (io/resource filename)]
    (when resource
      (edn/read-string (slurp resource)))))

(deftest simple-test-workflow-loads
  (testing "simple-test-v1.0.0.edn loads successfully"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")]
      (is (some? workflow)
          "Workflow file should load")

      (is (= :simple-test-v1 (:workflow/id workflow))
          "Workflow ID should match")

      (is (= "1.0.0" (:workflow/version workflow))
          "Version should match")

      (is (= :plan (:workflow/entry workflow))
          "Entry point should be :plan")

      (is (= 3 (count (:workflow/phases workflow)))
          "Should have 3 phases: plan, implement, done"))))

(deftest minimal-test-workflow-loads
  (testing "minimal-test-v1.0.0.edn loads successfully"
    (let [workflow (load-workflow-edn :minimal-test "1.0.0")]
      (is (some? workflow)
          "Workflow file should load")

      (is (= :minimal-test-v1 (:workflow/id workflow))
          "Workflow ID should match")

      (is (= "1.0.0" (:workflow/version workflow))
          "Version should match")

      (is (= :plan (:workflow/entry workflow))
          "Entry point should be :plan")

      (is (= 1 (count (:workflow/phases workflow)))
          "Should have 1 phase: plan only"))))

;; ============================================================================
;; Workflow structure validation tests
;; ============================================================================

(deftest simple-test-workflow-structure
  (testing "simple-test-v1 has valid structure"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          phases (:workflow/phases workflow)
          plan-phase (first (filter #(= :plan (:phase/id %)) phases))
          implement-phase (first (filter #(= :implement (:phase/id %)) phases))
          done-phase (first (filter #(= :done (:phase/id %)) phases))]

      ;; Plan phase validations
      (is (some? plan-phase) "Plan phase should exist")
      (is (= :planner (:phase/agent plan-phase))
          "Plan phase should use planner agent")
      (is (= [:implement] (mapv :target (:phase/next plan-phase)))
          "Plan should transition to implement")

      ;; Implement phase validations
      (is (some? implement-phase) "Implement phase should exist")
      (is (= :implementer (:phase/agent implement-phase))
          "Implement phase should use implementer agent")
      (is (= [:syntax-valid] (:phase/gates implement-phase))
          "Implement should have syntax gate")
      (is (= [:done] (mapv :target (:phase/next implement-phase)))
          "Implement should transition to done")

      ;; Done phase validations
      (is (some? done-phase) "Done phase should exist")
      (is (= :none (:phase/agent done-phase))
          "Done phase should have :none agent")
      (is (empty? (:phase/next done-phase))
          "Done phase should have no transitions"))))

(deftest minimal-test-workflow-structure
  (testing "minimal-test-v1 has valid structure"
    (let [workflow (load-workflow-edn :minimal-test "1.0.0")
          phases (:workflow/phases workflow)
          plan-phase (first phases)]

      (is (= :plan (:phase/id plan-phase))
          "Only phase should be plan")
      (is (= :planner (:phase/agent plan-phase))
          "Should use planner agent")
      (is (empty? (:phase/next plan-phase))
          "Plan should have no transitions (terminal)")
      (is (empty? (:phase/gates plan-phase))
          "Should have no gates for simplicity"))))

;; ============================================================================
;; Phase configuration validation tests
;; ============================================================================

(deftest simple-test-inner-loop-config
  (testing "simple-test phases have inner loop config"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          phases (:workflow/phases workflow)]

      (doseq [phase phases]
        (is (contains? phase :phase/inner-loop)
            (str "Phase " (:phase/id phase) " should have inner-loop config"))

        (let [inner-loop (:phase/inner-loop phase)]
          (is (contains? inner-loop :max-iterations)
              "Inner loop should specify max-iterations")
          (is (pos? (:max-iterations inner-loop))
              "Max iterations should be positive"))))))

(deftest simple-test-budget-config
  (testing "simple-test phases have budget config"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          phases (:workflow/phases workflow)]

      (doseq [phase phases]
        (is (contains? phase :phase/budget)
            (str "Phase " (:phase/id phase) " should have budget config"))

        (let [budget (:phase/budget phase)]
          (is (contains? budget :tokens)
              "Budget should specify tokens")
          (is (contains? budget :time-seconds)
              "Budget should specify time-seconds")
          (is (contains? budget :iterations)
              "Budget should specify iterations"))))))

;; ============================================================================
;; Workflow config validation tests
;; ============================================================================

(deftest simple-test-workflow-config
  (testing "simple-test has valid workflow config"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          config (:workflow/config workflow)]

      (is (some? config) "Workflow config should exist")
      (is (= :fail-fast (:failure-strategy config))
          "Should use fail-fast for testing")
      (is (= 20 (:max-total-iterations config))
          "Max iterations should be 20")
      (is (= 300 (:max-total-time-seconds config))
          "Max time should be 300 seconds")
      (is (= 20000 (:max-total-tokens config))
          "Max tokens should be 20000"))))

(deftest minimal-test-workflow-config
  (testing "minimal-test has valid workflow config"
    (let [workflow (load-workflow-edn :minimal-test "1.0.0")
          config (:workflow/config workflow)]

      (is (some? config) "Workflow config should exist")
      (is (= :fail-fast (:failure-strategy config))
          "Should use fail-fast for testing")
      (is (= 5 (:max-total-iterations config))
          "Max iterations should be 5 (very low)")
      (is (= 60 (:max-total-time-seconds config))
          "Max time should be 60 seconds")
      (is (= 5000 (:max-total-tokens config))
          "Max tokens should be 5000 (very low)"))))

;; ============================================================================
;; Metadata validation tests
;; ============================================================================

(deftest workflow-metadata-present
  (testing "Test workflows have proper metadata"
    (doseq [[wf-id version] [[:simple-test "1.0.0"]
                              [:minimal-test "1.0.0"]]]
      (let [workflow (load-workflow-edn wf-id version)]
        (is (contains? workflow :workflow/id)
            "Should have workflow ID")
        (is (contains? workflow :workflow/version)
            "Should have version")
        (is (contains? workflow :workflow/name)
            "Should have name")
        (is (contains? workflow :workflow/description)
            "Should have description")
        (is (contains? workflow :workflow/created-at)
            "Should have created-at timestamp")
        (is (contains? workflow :workflow/task-types)
            "Should have task-types")
        (is (contains? workflow :workflow/entry)
            "Should have entry phase")))))

;; ============================================================================
;; Task type validation tests
;; ============================================================================

(deftest test-workflows-task-types
  (testing "Test workflows specify appropriate task types"
    (let [simple-workflow (load-workflow-edn :simple-test "1.0.0")
          minimal-workflow (load-workflow-edn :minimal-test "1.0.0")]

      (is (some #{:test} (:workflow/task-types simple-workflow))
          "simple-test should include :test task type")

      (is (some #{:test :unit-test} (:workflow/task-types minimal-workflow))
          "minimal-test should include :test and :unit-test task types"))))

;; ============================================================================
;; Phase transition validation tests
;; ============================================================================

(deftest phase-transitions-valid
  (testing "Phase transitions reference existing phases"
    (doseq [[wf-id version] [[:simple-test "1.0.0"]
                              [:minimal-test "1.0.0"]]]
      (let [workflow (load-workflow-edn wf-id version)
            phases (:workflow/phases workflow)
            phase-ids (set (map :phase/id phases))]

        (doseq [phase phases]
          (let [transitions (:phase/next phase)
                target-ids (set (map :target transitions))]
            ;; All target IDs should reference existing phases or be empty
            (is (every? #(or (contains? phase-ids %)
                             (empty? transitions))
                        target-ids)
                (str "Phase " (:phase/id phase)
                     " references non-existent phases: "
                     (remove phase-ids target-ids)))))))))
