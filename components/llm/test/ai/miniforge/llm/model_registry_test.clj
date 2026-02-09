(ns ai.miniforge.llm.model-registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.model-registry :as registry]))

(deftest test-get-model
  (testing "Get model by keyword"
    (let [opus (registry/get-model :opus-4.6)]
      (is (= "claude-opus-4-6" (:model-id opus)))
      (is (= :anthropic (:provider opus)))
      (is (= :exceptional (get-in opus [:capabilities :reasoning]))))

    (let [sonnet (registry/get-model :sonnet-4.5)]
      (is (= "claude-sonnet-4-5-20250929" (:model-id sonnet)))
      (is (= :exceptional (get-in sonnet [:capabilities :code-generation]))))

    (let [haiku (registry/get-model :haiku-4.5)]
      (is (= "claude-haiku-4-5-20251001" (:model-id haiku)))
      (is (= :fast (get-in haiku [:capabilities :speed]))))))

(deftest test-get-models-by-capability
  (testing "Query by reasoning capability"
    (let [exceptional (registry/get-models-by-capability :reasoning :exceptional)]
      (is (seq exceptional))
      (is (some #{:opus-4.6} exceptional))
      (is (some #{:gpt-5.3-codex} exceptional)))

    (let [excellent (registry/get-models-by-capability :reasoning :excellent)]
      (is (seq excellent))
      (is (some #{:opus-4.6} excellent))
      (is (some #{:sonnet-4.5} excellent))
      (is (some #{:llama-3.3-70b} excellent))))

  (testing "Query by code-generation capability"
    (let [exceptional (registry/get-models-by-capability :code-generation :exceptional)]
      (is (seq exceptional))
      (is (some #{:sonnet-4.5} exceptional))
      (is (some #{:gpt-5.3-codex} exceptional))))

  (testing "Query by speed capability"
    (let [fast (registry/get-models-by-capability :speed :fast)]
      (is (seq fast))
      (is (some #{:haiku-4.5} fast))
      (is (some #{:gemini-2.0-flash} fast)))))

(deftest test-get-models-by-use-case
  (testing "Query by code-implementation use-case"
    (let [models (registry/get-models-by-use-case :code-implementation)]
      (is (seq models))
      (is (some #{:sonnet-4.5} models))
      (is (some #{:gpt-5.2-codex} models))))

  (testing "Query by workflow-planning use-case"
    (let [models (registry/get-models-by-use-case :workflow-planning)]
      (is (seq models))
      (is (some #{:opus-4.6} models))))

  (testing "Query by validation use-case"
    (let [models (registry/get-models-by-use-case :validation)]
      (is (seq models))
      (is (some #{:haiku-4.5} models)))))

(deftest test-get-models-by-provider
  (testing "Get Anthropic models"
    (let [models (registry/get-models-by-provider :anthropic)]
      (is (seq models))
      (is (some #{:opus-4.6} models))
      (is (some #{:sonnet-4.5} models))
      (is (some #{:haiku-4.5} models))))

  (testing "Get Google models"
    (let [models (registry/get-models-by-provider :google)]
      (is (seq models))
      (is (some #{:gemini-2.0-flash} models))
      (is (some #{:gemini-pro-2.0} models))))

  (testing "Get OpenAI models"
    (let [models (registry/get-models-by-provider :openai)]
      (is (seq models))
      (is (some #{:gpt-5.3-codex} models))
      (is (some #{:gpt-5.2-codex} models)))))

(deftest test-get-local-models
  (testing "Get all local models"
    (let [models (registry/get-local-models)]
      (is (seq models))
      (is (some #{:llama-3.3-70b} models))
      (is (some #{:qwen-2.5-coder-32b} models))
      (is (some #{:deepseek-coder-33b} models))
      (is (some #{:codellama-34b} models))
      ;; Should not include cloud models
      (is (not (some #{:opus-4.6} models)))
      (is (not (some #{:sonnet-4.5} models))))))

(deftest test-supports-large-context
  (testing "Check large context support"
    (is (registry/supports-large-context? :opus-4.6))
    (is (registry/supports-large-context? :sonnet-4.5))
    (is (registry/supports-large-context? :gemini-pro-2.0))
    (is (registry/supports-large-context? :gemini-2.0-flash))
    (is (registry/supports-large-context? :llama-3.3-70b)))

  (testing "Check with custom threshold"
    (is (registry/supports-large-context? :gemini-pro-2.0 1000000))
    (is (not (registry/supports-large-context? :opus-4.6 1000000)))
    (is (not (registry/supports-large-context? :haiku-4.5 300000)))))

(deftest test-recommend-models-for-task-type
  (testing "Recommend models for thinking-heavy tasks"
    (let [rec (registry/recommend-models-for-task-type :thinking-heavy)]
      (is (seq (:tier-1 rec)))
      (is (some #{:opus-4.6} (:tier-1 rec)))
      (is (:rationale rec))))

  (testing "Recommend models for execution-focused tasks"
    (let [rec (registry/recommend-models-for-task-type :execution-focused)]
      (is (seq (:tier-1 rec)))
      (is (some #{:sonnet-4.5} (:tier-1 rec)))
      (is (:rationale rec))))

  (testing "Recommend models for simple-validation tasks"
    (let [rec (registry/recommend-models-for-task-type :simple-validation)]
      (is (seq (:tier-1 rec)))
      (is (some #{:haiku-4.5} (:tier-1 rec)))
      (is (:rationale rec))))

  (testing "Recommend models for privacy-sensitive tasks"
    (let [rec (registry/recommend-models-for-task-type :privacy-sensitive)]
      (is (seq (:tier-1-local rec)))
      (is (some #{:llama-3.3-70b} (:tier-1-local rec)))
      (is (:rationale rec)))))

(deftest test-get-primary-recommendation
  (testing "Get primary recommendation"
    (is (= :opus-4.6 (registry/get-primary-recommendation :thinking-heavy)))
    (is (= :sonnet-4.5 (registry/get-primary-recommendation :execution-focused)))
    (is (= :haiku-4.5 (registry/get-primary-recommendation :simple-validation)))
    (is (= :gemini-pro-2.0 (registry/get-primary-recommendation :large-context)))))

(deftest test-model-registry-completeness
  (testing "All 16 models are present"
    (is (= 16 (count registry/model-registry))))

  (testing "All models have required fields"
    (doseq [[model-key model-data] registry/model-registry]
      (is (:model-id model-data) (str model-key " missing :model-id"))
      (is (:provider model-data) (str model-key " missing :provider"))
      (is (:backend model-data) (str model-key " missing :backend"))
      (is (:family model-data) (str model-key " missing :family"))
      (is (:tier model-data) (str model-key " missing :tier"))
      (is (:capabilities model-data) (str model-key " missing :capabilities"))
      (is (:best-for model-data) (str model-key " missing :best-for"))
      (is (:use-cases model-data) (str model-key " missing :use-cases")))))
