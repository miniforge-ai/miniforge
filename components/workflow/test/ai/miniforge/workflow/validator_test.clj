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

(ns ai.miniforge.workflow.validator-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.validator :as validator]))

(deftest load-schema-test
  (testing "Load workflow schema from resources"
    (let [schema (validator/load-schema)]
      (is (some? schema) "Schema should be loaded")
      (is (map? schema) "Schema should be a map")
      (is (contains? schema :workflow/schema) "Schema should contain :workflow/schema key"))))

(deftest validate-schema-test
  (testing "Valid workflow config passes schema validation"
    (let [config {:workflow/id :test-workflow
                  :workflow/version "1.0.0"
                  :workflow/name "Test Workflow"
                  :workflow/description "A test workflow"
                  :workflow/created-at (java.util.Date.)
                  :workflow/task-types [:test]
                  :workflow/type :test
                  :workflow/phases
                  [{:phase/id :start
                    :phase/name "Start"
                    :phase/agent :planner
                    :phase/next [{:target :end}]}
                   {:phase/id :end
                    :phase/name "End"
                    :phase/agent :none
                    :phase/next []}]
                  :workflow/entry-phase :start
                  :workflow/exit-phases [:end]}
          result (validator/validate-schema config)]
      (is (:valid? result) "Valid config should pass")
      (is (empty? (:errors result)) "Valid config should have no errors")))

  (testing "Missing required keys fail validation"
    (let [config {:workflow/version "1.0.0"}
          result (validator/validate-schema config)]
      (is (not (:valid? result)) "Invalid config should fail")
      (is (seq (:errors result)) "Invalid config should have errors")
      (is (some #(re-find #"workflow/id" %) (:errors result))
          "Should report missing workflow/id")))

  (testing "Invalid phases structure fails validation"
    (let [config {:workflow/id :test
                  :workflow/version "1.0.0"
                  :workflow/type :test
                  :workflow/phases "not-a-vector"
                  :workflow/entry-phase :start
                  :workflow/exit-phases [:end]}
          result (validator/validate-schema config)]
      (is (not (:valid? result)) "Invalid phases should fail")
      (is (some #(re-find #"vector" %) (:errors result))
          "Should report phases must be a vector")))

  (testing "Phase missing required keys"
    (let [config {:workflow/id :test
                  :workflow/version "1.0.0"
                  :workflow/type :test
                  :workflow/phases
                  [{:phase/name "Start"}]  ; Missing :phase/id
                  :workflow/entry-phase :start
                  :workflow/exit-phases [:end]}
          result (validator/validate-schema config)]
      (is (not (:valid? result)) "Phase with missing keys should fail")
      (is (some #(re-find #"phase/id" %) (:errors result))
          "Should report missing phase/id"))))

(deftest validate-dag-test
  (testing "Valid DAG passes validation"
    (let [config {:workflow/id :test
                  :workflow/version "1.0.0"
                  :workflow/name "Test DAG"
                  :workflow/description "A test DAG workflow"
                  :workflow/created-at (java.util.Date.)
                  :workflow/task-types [:test]
                  :workflow/type :test
                  :workflow/phases
                  [{:phase/id :start
                    :phase/name "Start"
                    :phase/agent :planner
                    :phase/next [{:target :end}]}
                   {:phase/id :end
                    :phase/name "End"
                    :phase/agent :none
                    :phase/next []}]
                  :workflow/entry-phase :start
                  :workflow/exit-phases [:end]}
          result (validator/validate-dag config)]
      (is (:valid? result) "Valid DAG should pass")
      (is (empty? (:errors result)) "Valid DAG should have no errors")))

  ;; Note: We no longer check for cycles as they are valid for retry/rollback patterns
  ;; This test is commented out
  #_(testing "Cycle detection fails validation"
      (let [config {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/type :test
                    :workflow/phases
                    [{:phase/id :a
                      :phase/name "A"
                      :phase/agent-type :planner
                      :phase/on-success {:transition/target :b}}
                     {:phase/id :b
                      :phase/name "B"
                      :phase/agent-type :implementer
                      :phase/on-success {:transition/target :a}}]  ; Cycle: a -> b -> a
                    :workflow/entry-phase :a
                    :workflow/exit-phases [:b]}
            result (validator/validate-dag config)]
        (is (not (:valid? result)) "Cycle should fail validation")
        (is (some #(re-find #"cycle" %) (:errors result))
            "Should report cycle")))

  (testing "Non-existent phase reference fails validation"
    (let [config {:workflow/id :test
                  :workflow/version "1.0.0"
                  :workflow/name "Test"
                  :workflow/description "Test"
                  :workflow/created-at (java.util.Date.)
                  :workflow/task-types [:test]
                  :workflow/type :test
                  :workflow/phases
                  [{:phase/id :start
                    :phase/name "Start"
                    :phase/agent :planner
                    :phase/next [{:target :missing}]}]  ; References non-existent phase
                  :workflow/entry-phase :start
                  :workflow/exit-phases [:end]}
          result (validator/validate-dag config)]
      (is (not (:valid? result)) "Non-existent phase reference should fail")
      (is (some #(re-find #"unknown on-success target" %) (:errors result))
          "Should report unknown target")))

  ;; TODO: Validator doesn't currently validate :workflow/entry-phase
  ;; It just uses first phase by default. Uncomment when implemented.
  #_(testing "Non-existent entry phase fails validation"
      (let [config {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/name "Test"
                    :workflow/description "Test"
                    :workflow/created-at (java.util.Date.)
                    :workflow/task-types [:test]
                    :workflow/type :test
                    :workflow/phases
                    [{:phase/id :start
                      :phase/name "Start"
                      :phase/agent :planner
                      :phase/next []}]
                    :workflow/entry-phase :missing  ; Non-existent entry phase
                    :workflow/exit-phases [:start]}
            result (validator/validate-dag config)]
        (is (not (:valid? result)) "Non-existent entry phase should fail")
        (is (some #(re-find #"Entry phase" %) (:errors result))
            "Should report entry phase not found")))

  (testing "Unreachable phases fail validation"
    (let [config {:workflow/id :test
                  :workflow/version "1.0.0"
                  :workflow/name "Test"
                  :workflow/description "Test"
                  :workflow/created-at (java.util.Date.)
                  :workflow/task-types [:test]
                  :workflow/type :test
                  :workflow/phases
                  [{:phase/id :start
                    :phase/name "Start"
                    :phase/agent :planner
                    :phase/next [{:target :done}]}
                   {:phase/id :orphan
                    :phase/name "Orphan"
                    :phase/agent :implementer
                    :phase/next [{:target :done}]}
                   {:phase/id :done
                    :phase/name "Done"
                    :phase/agent :none
                    :phase/next []}]  ; start skips orphan in compiled-machine path
                  :workflow/entry-phase :start
                  :workflow/exit-phases [:done]}
          result (validator/validate-dag config)]
      (is (not (:valid? result)) "Unreachable phases should fail")
      (is (some #(re-find #"unreachable phases" %) (:errors result))
          "Should report unreachable phases"))))

(deftest validate-budgets-test
  (testing "Valid budgets pass validation"
    (let [config {:workflow/id :test
                  :workflow/version "1.0.0"
                  :workflow/name "Test Budget"
                  :workflow/description "A test budget workflow"
                  :workflow/created-at (java.util.Date.)
                  :workflow/task-types [:test]
                  :workflow/type :test
                  :workflow/phases []
                  :workflow/entry-phase :start
                  :workflow/exit-phases [:end]
                  :workflow/budgets {:budget/tokens 10000
                                     :budget/cost-usd 5.0
                                     :budget/duration-ms 60000}}
          result (validator/validate-budgets config)]
      (is (:valid? result) "Valid budgets should pass")
      (is (empty? (:errors result)) "Valid budgets should have no errors")))

  (testing "Negative budget values fail validation"
    (let [config {:workflow/id :test
                  :workflow/version "1.0.0"
                  :workflow/type :test
                  :workflow/phases []
                  :workflow/entry-phase :start
                  :workflow/exit-phases [:end]
                  :workflow/budgets {:budget/tokens -100}}
          result (validator/validate-budgets config)]
      (is (not (:valid? result)) "Negative budget should fail")
      (is (some #(re-find #"positive" %) (:errors result))
          "Should report budget must be positive")))

  (testing "Config without budgets passes validation"
    (let [config {:workflow/id :test
                  :workflow/version "1.0.0"
                  :workflow/name "Test No Budget"
                  :workflow/description "A test workflow without budgets"
                  :workflow/created-at (java.util.Date.)
                  :workflow/task-types [:test]
                  :workflow/type :test
                  :workflow/phases []
                  :workflow/entry-phase :start
                  :workflow/exit-phases [:end]}
          result (validator/validate-budgets config)]
      (is (:valid? result) "Config without budgets should pass")
      (is (empty? (:errors result)) "Config without budgets should have no errors"))))

(deftest validate-workflow-test
  (testing "Complete valid workflow passes all validation"
    (let [config {:workflow/id :test-workflow
                  :workflow/version "1.0.0"
                  :workflow/name "Test Workflow"
                  :workflow/description "A complete test workflow"
                  :workflow/created-at (java.util.Date.)
                  :workflow/task-types [:plan :implement]
                  :workflow/type :test
                  :workflow/phases
                  [{:phase/id :plan
                    :phase/name "Plan"
                    :phase/agent :planner
                    :phase/next [{:target :implement}]}
                   {:phase/id :implement
                    :phase/name "Implement"
                    :phase/agent :implementer
                    :phase/next [{:target :done}]}
                   {:phase/id :done
                    :phase/name "Done"
                    :phase/agent :none
                    :phase/next []}]
                  :workflow/budgets {:budget/tokens 50000
                                     :budget/cost-usd 2.5}
                  :workflow/entry-phase :plan
                  :workflow/exit-phases [:done]}
          result (validator/validate-workflow config)]
      (is (:valid? result) "Complete valid workflow should pass")
      (is (empty? (:errors result)) "Complete valid workflow should have no errors")))

  (testing "Workflow with multiple validation errors"
    (let [config {:workflow/id :test
                  :workflow/version "1.0.0"
                  :workflow/name "Test"
                  :workflow/description "Test"
                  :workflow/created-at (java.util.Date.)
                  :workflow/task-types [:test]
                  ;; Missing :workflow/type
                  :workflow/phases
                  [{:phase/id :start
                    :phase/name "Start"
                    :phase/agent :planner
                    :phase/next [{:target :missing}]}]  ; References non-existent phase
                  :workflow/entry-phase :start
                  :workflow/exit-phases [:end]
                  :workflow/budgets {:budget/tokens -100}}  ; Negative budget
          result (validator/validate-workflow config)]
      (is (not (:valid? result)) "Invalid workflow should fail")
      (is (>= (count (:errors result)) 2) "Should report multiple errors")
      ;; Should have errors from compiled-machine and budget validation
      (is (some #(re-find #"unknown on-success target" %) (:errors result))
          "Should report unknown target")
      (is (some #(re-find #"positive" %) (:errors result))
          "Should report negative budget"))))
