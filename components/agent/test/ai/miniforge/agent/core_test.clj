(ns ai.miniforge.agent.core-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.agent.core :as core]
            [ai.miniforge.agent.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def test-task
  {:task/id (random-uuid)
   :task/type :implement
   :task/status :pending
   :task/inputs []})

;------------------------------------------------------------------------------ Layer 1
;; Metrics tests

(deftest make-metrics-test
  (testing "creates initial metrics"
    (let [metrics (core/make-metrics)]
      (is (map? metrics))
      (is (= 0 (:tokens-input metrics)))
      (is (= 0 (:tokens-output metrics)))
      (is (= 0.0 (:cost-usd metrics)))
      (is (= 0 (:llm-calls metrics))))))

(deftest update-metrics-test
  (testing "updates metrics with new values"
    (let [initial (core/make-metrics)
          updated (core/update-metrics initial {:input-tokens 100
                                                :output-tokens 50
                                                :duration-ms 1000
                                                :cost 0.01})]
      (is (= 100 (:tokens-input updated)))
      (is (= 50 (:tokens-output updated)))
      (is (= 150 (:tokens-total updated)))
      (is (= 1000 (:duration-ms updated)))
      (is (= 1 (:llm-calls updated)))))

  (testing "handles nil values"
    (let [initial (core/make-metrics)
          updated (core/update-metrics initial {})]
      (is (= 0 (:tokens-input updated)))
      (is (= 1 (:llm-calls updated))))))

(deftest estimate-cost-test
  (testing "calculates sonnet pricing"
    (let [cost (core/estimate-cost 1000000 500000 "claude-sonnet-4")]
      ;; Input: 1M * $3/1M = $3.00
      ;; Output: 500K * $15/1M = $7.50
      ;; Total: $10.50
      (is (> cost 10.0))
      (is (< cost 11.0))))

  (testing "calculates opus pricing"
    (let [cost (core/estimate-cost 1000000 500000 "claude-opus-4")]
      ;; Input: 1M * $15/1M = $15.00
      ;; Output: 500K * $75/1M = $37.50
      ;; Total: $52.50
      (is (> cost 50.0))
      (is (< cost 55.0))))

  (testing "uses default pricing for unknown model"
    (let [cost (core/estimate-cost 1000 500 "unknown-model")]
      (is (pos? cost)))))

;------------------------------------------------------------------------------ Layer 2
;; Agent creation tests

(deftest create-agent-test
  (testing "creates BaseAgent record"
    (let [agent (core/create-agent :implementer)]
      (is (instance? ai.miniforge.agent.core.BaseAgent agent))
      (is (= :implementer (:role agent)))
      (is (uuid? (:id agent)))
      (is (set? (:capabilities agent)))
      (is (map? (:config agent)))))

  (testing "applies role defaults"
    (let [agent (core/create-agent :planner)]
      (is (= 0.7 (get-in agent [:config :temperature])))
      (is (= 16000 (get-in agent [:config :max-tokens])))))

  (testing "overrides with custom config"
    (let [agent (core/create-agent :implementer {:model "custom-model"
                                                  :temperature 0.9})]
      (is (= "custom-model" (get-in agent [:config :model])))
      (is (= 0.9 (get-in agent [:config :temperature]))))))

(deftest create-agent-map-test
  (testing "returns map with agent keys"
    (let [m (core/create-agent-map :tester)]
      (is (contains? m :agent/id))
      (is (contains? m :agent/role))
      (is (contains? m :agent/capabilities))
      (is (contains? m :agent/memory))
      (is (contains? m :agent/config)))))

;------------------------------------------------------------------------------ Layer 3
;; Agent protocol implementation tests

(deftest agent-invoke-test
  (testing "invoke with mock LLM returns structured output"
    (let [agent (core/create-agent :implementer)
          mock-llm (core/create-mock-llm {:content "Generated code"
                                          :usage {:input-tokens 100 :output-tokens 50}
                                          :model "mock"})
          result (proto/invoke agent test-task {:llm-backend mock-llm})]
      (is (:success result))
      (is (vector? (:outputs result)))
      (is (vector? (:decisions result)))
      (is (vector? (:signals result)))
      (is (map? (:metrics result)))))

  (testing "invoke without LLM returns mock output"
    (let [agent (core/create-agent :implementer)
          result (proto/invoke agent test-task {})]
      (is (:success result))
      (is (contains? result :signals))
      (is (some #{:mock-execution} (:signals result))))))

(deftest agent-validate-test
  (testing "validates correct structure"
    (let [agent (core/create-agent :implementer)
          output {:success true :outputs [] :decisions [] :signals []}
          result (proto/validate agent output {})]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "rejects non-map output"
    (let [agent (core/create-agent :implementer)
          result (proto/validate agent "string" {})]
      (is (not (:valid? result)))
      (is (some #(re-find #"must be a map" %) (:errors result)))))

  (testing "rejects output without success"
    (let [agent (core/create-agent :implementer)
          result (proto/validate agent {:outputs []} {})]
      (is (not (:valid? result)))
      (is (some #(re-find #"success" %) (:errors result)))))

  (testing "rejects output without outputs vector"
    (let [agent (core/create-agent :implementer)
          result (proto/validate agent {:success true} {})]
      (is (not (:valid? result)))
      (is (some #(re-find #"outputs" %) (:errors result))))))

(deftest agent-repair-test
  (testing "returns original when no errors"
    (let [agent (core/create-agent :implementer)
          output {:success true :outputs []}
          result (proto/repair agent output [] {})]
      (is (:success result))
      (is (= output (:repaired result)))
      (is (empty? (:changes result)))))

  (testing "wraps invalid output with errors"
    (let [agent (core/create-agent :implementer)
          result (proto/repair agent "invalid" ["Error 1" "Error 2"] {})]
      (is (not (:success result)))
      (is (map? (:repaired result)))
      (is (= "invalid" (get-in result [:repaired :original])))
      (is (= ["Error 1" "Error 2"] (get-in result [:repaired :errors]))))))

(deftest agent-lifecycle-test
  (testing "init updates agent config"
    (let [agent (core/create-agent :implementer)
          initialized (proto/init agent {:max-tokens 2000})]
      (is (= 2000 (get-in initialized [:config :max-tokens])))
      (is (= :ready (get-in initialized [:state :status])))))

  (testing "status returns agent info"
    (let [agent (core/create-agent :implementer)
          initialized (proto/init agent {})
          status (proto/status initialized)]
      (is (= (:id agent) (:agent-id status)))
      (is (= :implementer (:role status)))))

  (testing "shutdown updates state"
    (let [agent (core/create-agent :implementer)
          shutdown (proto/shutdown agent)]
      (is (= :shutdown (get-in shutdown [:state :status])))
      (is (some? (get-in shutdown [:state :shutdown-at]))))))

;------------------------------------------------------------------------------ Layer 4
;; Executor tests

(deftest executor-test
  (testing "creates executor with defaults"
    (let [executor (core/create-executor)]
      (is (instance? ai.miniforge.agent.core.DefaultExecutor executor))))

  (testing "executes agent with mock LLM"
    (let [executor (core/create-executor)
          agent (core/create-agent :implementer)
          mock-llm (core/create-mock-llm {:content "Code output"
                                          :usage {:input-tokens 100 :output-tokens 50}
                                          :model "mock"})
          result (proto/execute executor agent test-task {:llm-backend mock-llm})]
      (is (:success result))
      (is (contains? result :metrics))
      (is (contains? result :agent-id))
      (is (contains? result :task-id))
      (is (contains? result :executed-at))))

  (testing "handles execution errors gracefully"
    (let [executor (core/create-executor)
          ;; Create a mock agent that throws
          agent (reify
                  proto/Agent
                  (invoke [_ _ _]
                    (throw (ex-info "Test error" {:type :test})))
                  (validate [_ _output _]
                    {:valid? true :errors [] :warnings []})
                  (repair [_ output _ _]
                    {:repaired output :changes [] :success true})

                  proto/AgentLifecycle
                  (init [this _] this)
                  (status [_] {:status :ready})
                  (shutdown [this] this))
          result (proto/execute executor agent test-task {})]
      (is (not (:success result)))
      (is (contains? result :error)))))

;------------------------------------------------------------------------------ Layer 5
;; Mock LLM tests

(deftest mock-llm-test
  (testing "returns configured response"
    (let [mock (core/create-mock-llm {:content "Hello"
                                      :usage {:input-tokens 10 :output-tokens 5}
                                      :model "test"})
          result (proto/complete mock [{:role :user :content "Hi"}] {})]
      (is (= "Hello" (:content result)))
      (is (= 10 (get-in result [:usage :input-tokens])))))

  (testing "returns default response when none configured"
    (let [mock (core/create-mock-llm)
          result (proto/complete mock [] {})]
      (is (string? (:content result)))
      (is (map? (:usage result)))))

  (testing "cycles through sequence"
    (let [mock (core/create-mock-llm [{:content "A" :usage {} :model "m"}
                                      {:content "B" :usage {} :model "m"}
                                      {:content "C" :usage {} :model "m"}])
          r1 (proto/complete mock [] {})
          r2 (proto/complete mock [] {})
          r3 (proto/complete mock [] {})]
      (is (= "A" (:content r1)))
      (is (= "B" (:content r2)))
      (is (= "C" (:content r3))))))

;------------------------------------------------------------------------------ Layer 6
;; Task type mapping tests

(deftest task-type-artifact-type-test
  (testing "maps task types to artifact types"
    (is (= :plan (core/task-type->artifact-type :plan)))
    (is (= :adr (core/task-type->artifact-type :design)))
    (is (= :code (core/task-type->artifact-type :implement)))
    (is (= :test (core/task-type->artifact-type :test)))
    (is (= :review (core/task-type->artifact-type :review)))
    (is (= :manifest (core/task-type->artifact-type :deploy))))

  (testing "defaults to :code for unknown types"
    (is (= :code (core/task-type->artifact-type :unknown)))))

;------------------------------------------------------------------------------ Layer 7
;; Role configuration tests

(deftest role-config-test
  (testing "all roles have valid temperatures"
    (doseq [[role config] core/default-role-configs]
      (let [temp (:temperature config)]
        (is (>= temp 0.0) (str role " temperature too low"))
        (is (<= temp 1.0) (str role " temperature too high")))))

  (testing "all roles have reasonable token limits"
    (doseq [[role config] core/default-role-configs]
      (let [tokens (:max-tokens config)]
        (is (pos? tokens) (str role " must have positive max-tokens"))
        (is (<= tokens 100000) (str role " max-tokens too high")))))

  (testing "all roles have budgets"
    (doseq [[role config] core/default-role-configs]
      (let [budget (:budget config)]
        (is (map? budget) (str role " must have budget"))
        (is (pos? (:tokens budget)) (str role " must have token budget"))
        (is (pos? (:cost-usd budget)) (str role " must have cost budget"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.core-test)

  ;; Run specific test
  (test/test-var #'agent-invoke-test)

  :leave-this-here)
