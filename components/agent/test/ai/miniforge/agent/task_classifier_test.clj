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

(ns ai.miniforge.agent.task-classifier-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.task-classifier :as classifier]))

(deftest test-classify-by-phase
  (testing "Planning phase classified as thinking-heavy"
    (let [result (classifier/classify-by-phase :plan)]
      (is (= :thinking-heavy (:type result)))
      (is (>= (:confidence result) 0.9))))

  (testing "Implement phase classified as execution-focused"
    (let [result (classifier/classify-by-phase :implement)]
      (is (= :execution-focused (:type result)))
      (is (>= (:confidence result) 0.9))))

  (testing "Validate phase classified as simple-validation"
    (let [result (classifier/classify-by-phase :validate)]
      (is (= :simple-validation (:type result)))
      (is (>= (:confidence result) 0.9))))

  (testing "Unknown phase returns nil"
    (let [result (classifier/classify-by-phase :unknown)]
      (is (nil? result)))))

(deftest test-classify-by-agent-type
  (testing "Planner agent classified as thinking-heavy"
    (let [result (classifier/classify-by-agent-type :planner-agent)]
      (is (= :thinking-heavy (:type result)))
      (is (>= (:confidence result) 0.8))))

  (testing "Implementer agent classified as execution-focused"
    (let [result (classifier/classify-by-agent-type :implementer-agent)]
      (is (= :execution-focused (:type result)))
      (is (>= (:confidence result) 0.8))))

  (testing "Validator agent classified as simple-validation"
    (let [result (classifier/classify-by-agent-type :validator-agent)]
      (is (= :simple-validation (:type result)))
      (is (>= (:confidence result) 0.8)))))

(deftest test-classify-by-keywords
  (testing "Architecture keywords trigger thinking-heavy"
    (let [result (classifier/classify-by-keywords
                  "Design the architecture for the system"
                  "Architecture Planning")]
      (is (= :thinking-heavy (:type result)))
      (is (> (:confidence result) 0.0))))

  (testing "Implementation keywords trigger execution-focused"
    (let [result (classifier/classify-by-keywords
                  "Implement the feature and write code"
                  "Code Implementation")]
      (is (= :execution-focused (:type result)))
      (is (> (:confidence result) 0.0))))

  (testing "Validation keywords trigger simple-validation"
    (let [result (classifier/classify-by-keywords
                  "Validate syntax and format code"
                  "Quick Validation")]
      (is (= :simple-validation (:type result)))
      (is (> (:confidence result) 0.0)))))

(deftest test-classify-by-context-size
  (testing "Large context triggers large-context classification"
    (let [result (classifier/classify-by-context-size {:context-tokens 150000})]
      (is (= :large-context (:type result)))
      (is (>= (:confidence result) 0.95))))

  (testing "Small context returns nil"
    (let [result (classifier/classify-by-context-size {:context-tokens 5000})]
      (is (nil? result)))))

(deftest test-classify-by-privacy
  (testing "Privacy required triggers privacy-sensitive"
    (let [result (classifier/classify-by-privacy {:privacy-required true})]
      (is (= :privacy-sensitive (:type result)))
      (is (= 1.0 (:confidence result)))))

  (testing "Offline mode triggers privacy-sensitive"
    (let [result (classifier/classify-by-privacy {:offline-mode true})]
      (is (= :privacy-sensitive (:type result)))
      (is (= 1.0 (:confidence result)))))

  (testing "No privacy requirements returns nil"
    (let [result (classifier/classify-by-privacy {})]
      (is (nil? result)))))

(deftest test-classify-by-cost
  (testing "Cost limit triggers cost-optimized"
    (let [result (classifier/classify-by-cost {:cost-limit 0.05})]
      (is (= :cost-optimized (:type result)))
      (is (>= (:confidence result) 0.9))))

  (testing "Budget constrained triggers cost-optimized"
    (let [result (classifier/classify-by-cost {:budget-constrained true})]
      (is (= :cost-optimized (:type result)))
      (is (>= (:confidence result) 0.9)))))

(deftest test-classify-task-comprehensive
  (testing "Planning task with all signals"
    (let [task {:phase :plan
                :agent-type :planner-agent
                :description "Design architecture for distributed system"
                :title "Architecture Design"}
          result (classifier/classify-task task)]
      (is (= :thinking-heavy (:type result)))
      (is (>= (:confidence result) 0.8))
      (is (:reason result))))

  (testing "Implementation task"
    (let [task {:phase :implement
                :agent-type :implementer-agent
                :description "Write code for user authentication"
                :title "Implement Auth"}
          result (classifier/classify-task task)]
      (is (= :execution-focused (:type result)))
      (is (>= (:confidence result) 0.8))
      (is (:reason result))))

  (testing "Validation task"
    (let [task {:phase :validate
                :agent-type :validator-agent
                :description "Check syntax and format"
                :title "Validate Code"}
          result (classifier/classify-task task)]
      (is (= :simple-validation (:type result)))
      (is (>= (:confidence result) 0.8))
      (is (:reason result))))

  (testing "Privacy-sensitive task overrides other signals"
    (let [task {:phase :implement
                :agent-type :implementer-agent
                :privacy-required true
                :description "Implement banking feature"}
          result (classifier/classify-task task)]
      (is (= :privacy-sensitive (:type result)))
      (is (= 1.0 (:confidence result)))
      (is (:reason result))))

  (testing "Large context task overrides normal signals"
    (let [task {:phase :implement
                :context-tokens 200000
                :description "Refactor entire codebase"}
          result (classifier/classify-task task)]
      (is (= :large-context (:type result)))
      (is (>= (:confidence result) 0.95))))

  (testing "Unknown task defaults to execution-focused"
    (let [task {:description "Do something"}
          result (classifier/classify-task task)]
      (is (= :execution-focused (:type result)))
      (is (<= (:confidence result) 0.6)))))

(deftest test-extract-keywords
  (testing "Extract and normalize keywords"
    (let [keywords (classifier/extract-keywords "Design the Architecture for System")]
      (is (contains? keywords :design))
      (is (contains? keywords :architecture))
      (is (contains? keywords :system)))))

(deftest test-extract-context-size
  (testing "Extract from context-tokens"
    (is (= 50000 (classifier/extract-context-size {:context-tokens 50000}))))

  (testing "Extract from estimated-loc"
    (is (= 10000 (classifier/extract-context-size {:estimated-loc 10000}))))

  (testing "Extract from file-count"
    (is (= 5000 (classifier/extract-context-size {:file-count 10}))))

  (testing "Default to 0"
    (is (= 0 (classifier/extract-context-size {})))))

(deftest test-get-task-characteristics
  (testing "Extract all characteristics"
    (let [task {:phase :plan
                :agent-type :planner-agent
                :description "Design system architecture"
                :title "Architecture"
                :context-tokens 50000
                :privacy-required true}
          chars (classifier/get-task-characteristics task)]
      (is (= :plan (:phase chars)))
      (is (= :planner-agent (:agent-type chars)))
      (is (seq (:keywords chars)))
      (is (= 50000 (:context-size chars)))
      (is (true? (:privacy-required chars))))))
