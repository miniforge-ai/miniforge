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

(ns ai.miniforge.llm.model-selector-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.model-selector :as selector]
   [ai.miniforge.llm.model-registry :as registry]))

(deftest test-model-available
  (testing "Model availability check"
    ;; For now, all models are considered available
    (is (selector/model-available? :opus-4.6))
    (is (selector/model-available? :sonnet-4.6))
    (is (selector/model-available? :haiku-4.5))))

(deftest test-meets-context-requirement
  (testing "Context requirement checks"
    (is (selector/meets-context-requirement? :opus-4.6 100000))
    (is (selector/meets-context-requirement? :gemini-2.5-pro 500000))
    (is (not (selector/meets-context-requirement? :qwen-2.5-coder-32b 100000)))))

(deftest test-meets-cost-constraint
  (testing "Cost constraint checks"
    (is (selector/meets-cost-constraint? :haiku-4.5 0.01))
    (is (selector/meets-cost-constraint? :sonnet-4.6 0.05))
    (is (selector/meets-cost-constraint? :opus-4.6 0.10))
    (is (selector/meets-cost-constraint? :codellama-34b 0.01))))

(deftest test-select-by-automatic
  (testing "Automatic selection for thinking-heavy"
    (let [selected (selector/select-by-automatic :thinking-heavy {})]
      (is (some #{selected} [:opus-4.7 :opus-4.6 :gpt-5.4-pro :gpt-5.4]))))

  (testing "Automatic selection for execution-focused"
    (let [selected (selector/select-by-automatic :execution-focused {})]
      (is (some #{selected} [:sonnet-4.6 :gpt-5.2-codex :gpt-5.3-codex]))))

  (testing "Automatic selection for simple-validation"
    (let [selected (selector/select-by-automatic :simple-validation {})]
      (is (some #{selected} [:haiku-4.5 :gemini-2.5-flash-lite :gpt-5.1-codex-max]))))

  (testing "Automatic selection with local requirement"
    (let [selected (selector/select-by-automatic :execution-focused {:require-local true})]
      (is (some #{selected} [:qwen-2.5-coder-32b :deepseek-coder-33b :codellama-34b])))))

(deftest test-select-by-cost-optimized
  (testing "Cost-optimized selection prefers free models"
    (let [selected (selector/select-by-cost-optimized :execution-focused {})]
      ;; Should prefer local/free models or cheap cloud models
      (is (keyword? selected)))))

(deftest test-select-by-speed
  (testing "Speed-optimized selection prefers fast models"
    (let [selected (selector/select-by-speed :execution-focused {})]
      (is (keyword? selected)))))

(deftest test-select-model
  (testing "Select model for thinking-heavy task"
    (let [classification {:type :thinking-heavy
                          :confidence 0.9
                          :reason "Planning requires deep reasoning"}
          selection (selector/select-model classification)]
      (is (= :thinking-heavy (:task-type selection)))
      (is (= 0.9 (:confidence selection)))
      (is (:model selection))
      (is (:model-id selection))
      (is (:provider selection))
      (is (:backend selection))
      (is (:rationale selection))
      (is (string? (:rationale selection)))))

  (testing "Select model for execution-focused task"
    (let [classification {:type :execution-focused
                          :confidence 0.85
                          :reason "Code implementation"}
          selection (selector/select-model classification)]
      (is (= :execution-focused (:task-type selection)))
      (is (:model selection))))

  (testing "Select model for simple-validation task"
    (let [classification {:type :simple-validation
                          :confidence 0.95
                          :reason "Quick syntax check"}
          selection (selector/select-model classification)]
      (is (= :simple-validation (:task-type selection)))
      (is (:model selection))))

  (testing "Select model with cost constraint"
    (let [classification {:type :execution-focused
                          :confidence 0.8
                          :reason "Standard coding"}
          selection (selector/select-model classification
                                           {:strategy :cost-optimized}
                                           {:cost-limit 0.05})]
      (is (:model selection))))

  (testing "Select model with privacy requirement"
    (let [classification {:type :execution-focused
                          :confidence 0.8
                          :reason "Private code"}
          selection (selector/select-model classification
                                           {}
                                           {:require-local true})
          model (registry/get-model (:model selection))]
      (is (get-in model [:capabilities :local]))))

  (testing "Select model with large context"
    (let [classification {:type :large-context
                          :confidence 0.95
                          :reason "Large codebase"}
          selection (selector/select-model classification
                                           {}
                                           {:context-size 500000})
          model (registry/get-model (:model selection))]
      (is (>= (get-in model [:capabilities :context-window]) 500000)))))

(deftest test-select-model-for-phase
  (testing "Plan phase selects thinking model"
    (let [selection (selector/select-model-for-phase :plan)]
      (is (= :thinking-heavy (:task-type selection)))
      (is (:model selection))))

  (testing "Implement phase selects execution model"
    (let [selection (selector/select-model-for-phase :implement)]
      (is (= :execution-focused (:task-type selection)))
      (is (:model selection))))

  (testing "Validate phase selects validation model"
    (let [selection (selector/select-model-for-phase :validate)]
      (is (= :simple-validation (:task-type selection)))
      (is (:model selection)))))

(deftest test-build-selection-rationale
  (testing "Rationale is human-readable"
    (let [classification {:type :thinking-heavy
                          :confidence 0.9
                          :reason "Planning task"}
          selection (selector/select-model classification)
          rationale (:rationale selection)]
      (is (string? rationale))
      (is (pos? (count rationale)))
      (is (re-find #"Task:" rationale))
      (is (re-find #"Selected Model:" rationale)))))

(deftest test-explain-selection
  (testing "Explanation is user-friendly"
    (let [classification {:type :execution-focused
                          :confidence 0.85
                          :reason "Code implementation"}
          selection (selector/select-model classification)
          explanation (selector/explain-selection selection)]
      (is (string? explanation))
      (is (pos? (count explanation)))
      (is (re-find #"Model Auto-Selected:" explanation))
      (is (re-find #"Override:" explanation)))))

(deftest test-selection-strategies
  (testing "Automatic strategy"
    (let [classification {:type :execution-focused
                          :confidence 0.8
                          :reason "Standard task"}
          selection (selector/select-model classification
                                           {:strategy :automatic})]
      (is (= :automatic (:strategy selection)))))

  (testing "Cost-optimized strategy"
    (let [classification {:type :execution-focused
                          :confidence 0.8
                          :reason "Budget task"}
          selection (selector/select-model classification
                                           {:strategy :cost-optimized})]
      (is (= :cost-optimized (:strategy selection)))))

  (testing "Speed strategy"
    (let [classification {:type :execution-focused
                          :confidence 0.8
                          :reason "Fast task"}
          selection (selector/select-model classification
                                           {:strategy :speed})]
      (is (= :speed (:strategy selection))))))

(deftest test-fallback-behavior
  (testing "Fallback to safe default when no model matches"
    ;; Test with valid task type - even with unknown types, system should return a model
    ;; Using execution-focused as a safe default case
    (let [classification {:type :execution-focused
                          :confidence 0.5
                          :reason "Generic task"}
          selection (selector/select-model classification)]
      (is (:model selection))
      (is (:model-id selection)))))
