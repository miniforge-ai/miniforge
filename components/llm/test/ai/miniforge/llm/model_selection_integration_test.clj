(ns ai.miniforge.llm.model-selection-integration-test
  "Integration tests for the complete intelligent model selection system.
   Tests the full flow: task -> classification -> model selection -> execution."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.llm.model-registry :as registry]
   [ai.miniforge.llm.model-selector :as selector]))

(deftest test-end-to-end-planning-task
  (testing "End-to-end: Planning task selects Opus"
    (let [;; Step 1: Create task description
          task {:phase :plan
                :agent-type :planner-agent
                :description "Design a distributed microservices architecture"
                :title "Architecture Design"}

          ;; Step 2: Classify task
          classification (agent/classify-task task)

          ;; Step 3: Select model
          selection (selector/select-model classification)]

      ;; Verify classification
      (is (= :thinking-heavy (:type classification)))
      (is (>= (:confidence classification) 0.8))

      ;; Verify selection
      (is (:model selection))
      (is (= :thinking-heavy (:task-type selection)))

      ;; Should select a flagship model for planning
      (is (some #{(:model selection)} [:opus-4.6 :gpt-5.3-codex :gemini-2.0-flash-thinking-exp]))

      ;; Verify model profile
      (let [model (registry/get-model (:model selection))]
        (is (#{:exceptional :excellent} (get-in model [:capabilities :reasoning])))))))

(deftest test-end-to-end-implementation-task
  (testing "End-to-end: Implementation task selects Sonnet"
    (let [task {:phase :implement
                :agent-type :implementer-agent
                :description "Implement user authentication service"
                :title "Implement Auth"}

          classification (agent/classify-task task)
          selection (selector/select-model classification)]

      ;; Verify classification
      (is (= :execution-focused (:type classification)))

      ;; Should select a workhorse model for implementation
      (is (some #{(:model selection)} [:sonnet-4.6 :gpt-5.2-codex :gpt-5.3-codex]))

      ;; Verify model has good code generation
      (let [model (registry/get-model (:model selection))]
        (is (#{:exceptional :excellent} (get-in model [:capabilities :code-generation])))))))

(deftest test-end-to-end-validation-task
  (testing "End-to-end: Validation task selects Haiku or fast model"
    (let [task {:phase :validate
                :agent-type :validator-agent
                :description "Validate syntax and formatting"
                :title "Quick Validation"}

          classification (agent/classify-task task)
          selection (selector/select-model classification)]

      ;; Verify classification
      (is (= :simple-validation (:type classification)))

      ;; Should select a fast/efficient model
      (is (some #{(:model selection)} [:haiku-4.5 :gemini-2.0-flash :gpt-5.1-codex-max]))

      ;; Verify model is fast or economical
      (let [model (registry/get-model (:model selection))]
        (is (#{:fast :very-fast :economical} (get-in model [:capabilities :speed])))))))

(deftest test-privacy-constraint
  (testing "Privacy constraint forces local model"
    (let [task {:phase :implement
                :agent-type :implementer-agent
                :privacy-required true
                :description "Implement banking feature"}

          classification (agent/classify-task task)
          selection (selector/select-model classification
                                           {}
                                           {:require-local true})]

      ;; Should select privacy-sensitive type
      (is (= :privacy-sensitive (:type classification)))

      ;; Should select a local model
      (let [model (registry/get-model (:model selection))]
        (is (get-in model [:capabilities :local]))
        (is (= :free (get-in model [:capabilities :cost])))))))

(deftest test-large-context-task
  (testing "Large context task selects Gemini"
    (let [task {:phase :implement
                :context-tokens 500000
                :description "Refactor entire codebase"}

          classification (agent/classify-task task)
          selection (selector/select-model classification
                                           {}
                                           {:context-size 500000})]

      ;; Should detect large context need
      (is (= :large-context (:type classification)))

      ;; Should select a model with large context window
      (let [model (registry/get-model (:model selection))]
        (is (>= (get-in model [:capabilities :context-window]) 500000))
        ;; Likely Gemini models with 1M+ context
        (is (some #{(:provider model)} [:google :anthropic]))))))

(deftest test-cost-optimized-strategy
  (testing "Cost-optimized strategy prefers cheaper models"
    (let [task {:phase :implement
                :agent-type :implementer-agent
                :description "Standard implementation task"}

          classification (agent/classify-task task)

          ;; Default automatic selection
          selection-auto (selector/select-model classification
                                                {:strategy :automatic})

          ;; Cost-optimized selection
          selection-cost (selector/select-model classification
                                                {:strategy :cost-optimized})]

      ;; Cost-optimized should prefer free or cheap models
      (let [model-cost (registry/get-model (:model selection-cost))]
        (is (#{:free :economical :moderate} (get-in model-cost [:capabilities :cost]))))

      ;; Both should be valid selections
      (is (:model selection-auto))
      (is (:model selection-cost)))))

(deftest test-selection-transparency
  (testing "Selection provides clear rationale"
    (let [task {:phase :plan
                :description "Architecture planning"}

          classification (agent/classify-task task)
          selection (selector/select-model classification)
          explanation (selector/explain-selection selection)]

      ;; Should have detailed rationale
      (is (string? (:rationale selection)))
      (is (pos? (count (:rationale selection))))
      (is (re-find #"Task:" (:rationale selection)))
      (is (re-find #"Model:" (:rationale selection)))

      ;; Should have user-facing explanation
      (is (string? explanation))
      (is (re-find #"Model Auto-Selected:" explanation))
      (is (re-find #"Override:" explanation)))))

(deftest test-multiple-task-types
  (testing "Different task types get appropriate models"
    (let [;; Create various task types
          tasks [{:phase :plan :title "Plan"}
                 {:phase :implement :title "Implement"}
                 {:phase :validate :title "Validate"}
                 {:phase :test :title "Test"}
                 {:phase :review :title "Review"}]

          ;; Classify and select for each
          selections (map (fn [task]
                            (let [classification (agent/classify-task task)]
                              {:task task
                               :classification classification
                               :selection (selector/select-model classification)}))
                          tasks)]

      ;; All should succeed
      (is (= 5 (count selections)))

      ;; All should have valid models
      (doseq [{:keys [selection]} selections]
        (is (:model selection))
        (is (:model-id selection))
        (is (:provider selection)))

      ;; Plan should use thinking model
      (let [plan-selection (-> selections first :selection)]
        (is (= :thinking-heavy (:task-type plan-selection))))

      ;; Implement should use execution model
      (let [impl-selection (-> selections second :selection)]
        (is (= :execution-focused (:task-type impl-selection))))

      ;; Validate should use simple model
      (let [validate-selection (-> selections (nth 2) :selection)]
        (is (= :simple-validation (:task-type validate-selection)))))))

(deftest test-fallback-behavior
  (testing "System has safe fallback when selection fails"
    (let [;; Minimal task with no clear signals
          task {:description "Generic task"}

          classification (agent/classify-task task)
          selection (selector/select-model classification)]

      ;; Should default to execution-focused
      (is (= :execution-focused (:type classification)))
      (is (<= (:confidence classification) 0.6))

      ;; Should still select a valid model (fallback to Sonnet)
      (is (:model selection))
      (is (:model-id selection)))))

(deftest test-cross-provider-selection
  (testing "System can select from multiple providers"
    (let [;; Create tasks that might favor different providers
          tasks [{:phase :plan :title "Complex reasoning"}
                 {:phase :implement :context-tokens 1500000 :title "Large codebase"}
                 {:phase :implement :privacy-required true :title "Private code"}]

          selections (map (fn [task]
                            (let [classification (agent/classify-task task)
                                  selection (selector/select-model
                                             classification
                                             {}
                                             {:context-size (:context-tokens task)
                                              :require-local (:privacy-required task)})]
                              {:task task
                               :selection selection
                               :provider (:provider selection)}))
                          tasks)]

      ;; All should succeed
      (is (= 3 (count selections)))

      ;; Large context should likely use Gemini
      (let [large-context (second selections)]
        (is (#{:google :anthropic} (:provider large-context))))

      ;; Privacy should use local (Meta, Alibaba, DeepSeek, etc.)
      (let [privacy (nth selections 2)]
        (is (#{:meta :alibaba :deepseek :zhipu} (:provider privacy)))))))

(deftest test-model-override
  (testing "Explicit model override bypasses automatic selection"
    ;; Note: This test documents expected behavior
    ;; In actual agent creation, passing :model explicitly
    ;; should skip automatic selection
    (let [task {:phase :validate :title "Validation"}
          classification (agent/classify-task task)

          ;; Normal selection would pick Haiku
          auto-selection (selector/select-model classification)]

      ;; Auto should pick validation model
      (is (= :simple-validation (:task-type auto-selection)))

      ;; If user explicitly specifies model in agent opts,
      ;; that should override automatic selection
      ;; This is tested at the agent layer, not here
      )))

(deftest test-selection-confidence
  (testing "Selection includes confidence from classification"
    (let [;; High-confidence task (clear phase signal)
          high-conf-task {:phase :plan
                          :agent-type :planner-agent
                          :description "Design architecture"}

          ;; Low-confidence task (ambiguous)
          low-conf-task {:description "Do something"}

          high-classification (agent/classify-task high-conf-task)
          low-classification (agent/classify-task low-conf-task)

          high-selection (selector/select-model high-classification)
          low-selection (selector/select-model low-classification)]

      ;; High confidence should be >= 0.8
      (is (>= (:confidence high-selection) 0.8))

      ;; Low confidence should be <= 0.6
      (is (<= (:confidence low-selection) 0.6))

      ;; Both should still produce valid selections
      (is (:model high-selection))
      (is (:model low-selection)))))

(deftest test-all-16-models-accessible
  (testing "All 16 models in registry can be selected"
    (let [;; Get all model keys
          all-models (keys registry/model-registry)]

      ;; Should have exactly 16 models
      (is (= 16 (count all-models)))

      ;; All should be queryable
      (doseq [model-key all-models]
        (let [model (registry/get-model model-key)]
          (is model (str "Model " model-key " should be accessible"))
          (is (:model-id model))
          (is (:provider model))
          (is (:capabilities model)))))))

(deftest test-selection-rationale-completeness
  (testing "Selection rationale includes all key information"
    (let [task {:phase :implement
                :description "Code implementation"
                :context-tokens 50000}

          classification (agent/classify-task task)
          selection (selector/select-model classification
                                           {:strategy :automatic}
                                           {:context-size 50000})]

      ;; Rationale should include:
      (is (re-find #"Task:" (:rationale selection)))
      (is (re-find #"confidence:" (:rationale selection)))
      (is (re-find #"Model:" (:rationale selection)))
      (is (re-find #"Strategy:" (:rationale selection)))

      ;; Should mention task type
      (is (re-find (re-pattern (name (:task-type selection)))
                   (:rationale selection))))))
