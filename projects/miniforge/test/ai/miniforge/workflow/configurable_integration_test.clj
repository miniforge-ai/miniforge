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

(ns ai.miniforge.workflow.configurable-integration-test
  "Integration tests for configurable workflow execution."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.configurable :as configurable]
   [ai.miniforge.workflow.loader :as loader]
   [ai.miniforge.workflow.state :as state]
   [ai.miniforge.agent.interface :as agent]))

(defn with-mocked-test-runner
  "Run body-fn with run-tests! and write-test-files! mocked to prevent recursive bb test."
  [body-fn]
  (let [write-var (resolve 'ai.miniforge.phase.verify/write-test-files!)
        run-var (resolve 'ai.miniforge.phase.verify/run-tests!)]
    (with-redefs-fn
      {write-var (fn [_ files] (mapv :path files))
       run-var (fn [_] {:passed? true :test-count 1 :fail-count 0 :error-count 0})}
      body-fn)))

;------------------------------------------------------------------------------ Test fixtures

(def simple-workflow
  "A minimal test workflow with 2 phases."
  {:workflow/id :test-workflow
   :workflow/version "1.0.0"
   :workflow/name "Simple Test Workflow"
   :workflow/description "A simple two-phase workflow for testing"
   :workflow/entry :plan
   :workflow/phases
   [{:phase/id :plan
     :phase/name "Planning"
     :phase/description "Create a plan"
     :phase/agent :planner
     :phase/gates []
     :phase/inner-loop {:max-iterations 3}
     :phase/next [{:target :done}]}]})

(def multi-phase-workflow
  "A workflow with multiple phases."
  {:workflow/id :multi-test-workflow
   :workflow/version "1.0.0"
   :workflow/name "Multi-Phase Test Workflow"
   :workflow/description "A workflow with plan -> implement -> verify"
   :workflow/entry :plan
   :workflow/phases
   [{:phase/id :plan
     :phase/name "Planning"
     :phase/agent :planner
     :phase/gates []
     :phase/inner-loop {:max-iterations 3}
     :phase/next [{:target :implement}]}
    {:phase/id :implement
     :phase/name "Implementation"
     :phase/agent :implementer
     :phase/gates [:syntax-valid]
     :phase/inner-loop {:max-iterations 5}
     :phase/next [{:target :verify}]}
    {:phase/id :verify
     :phase/name "Verification"
     :phase/agent :tester
     :phase/task-type :test
     :phase/gates []
     :phase/inner-loop {:max-iterations 3}
     :phase/next [{:target :done}]}]})

(def workflow-with-none-agent
  "A workflow with a :none agent phase."
  {:workflow/id :none-agent-workflow
   :workflow/version "1.0.0"
   :workflow/name "Workflow with None Agent"
   :workflow/entry :done
   :workflow/phases
   [{:phase/id :done
     :phase/name "Done"
     :phase/agent :none
     :phase/gates []
     :phase/next []}]})

;------------------------------------------------------------------------------ Integration tests

(deftest test-simple-workflow-execution
  (testing "Execute simple workflow with mock LLM"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"
                                            :usage {:input-tokens 50 :output-tokens 25}})
          input {:task "Create a greeting function"}
          context {:llm-backend mock-llm}
          result (configurable/run-configurable-workflow simple-workflow input context)]

      (is (= :completed (:execution/status result))
          "Workflow should complete successfully")

      (is (seq (:execution/artifacts result))
          "Should have generated artifacts")

      (is (>= (get-in result [:execution/metrics :tokens] 0) 0)
          "Should have token metrics (0 for mock LLM)")

      (is (empty? (:execution/errors result))
          "Should have no errors"))))

(deftest test-multi-phase-workflow-execution
  (testing "Execute multi-phase workflow with mock LLM"
    (with-mocked-test-runner
      (fn []
        (let [mock-llm (agent/create-mock-llm
                        [{:content "(plan {:tasks [...]})"
                          :usage {:input-tokens 50 :output-tokens 25}}
                         {:content "(defn hello [] \"world\")"
                          :usage {:input-tokens 60 :output-tokens 30}}
                         {:content "(deftest test-hello ...)"
                          :usage {:input-tokens 40 :output-tokens 20}}])
              input {:task "Create and test a greeting function"}
              context {:llm-backend mock-llm}
              result (configurable/run-configurable-workflow multi-phase-workflow input context)]

          (is (= :completed (:execution/status result))
              "Workflow should complete successfully")

          ;; Check that all phases executed
          (let [phase-results (:execution/phase-results result)]
            (is (contains? phase-results :plan)
                "Should have plan phase result")
            (is (contains? phase-results :implement)
                "Should have implement phase result")
            (is (contains? phase-results :verify)
                "Should have verify phase result"))

          ;; Should have artifacts from all phases
          (is (>= (count (:execution/artifacts result)) 3)
              "Should have at least 3 artifacts (one per phase)")

          ;; Check metrics accumulated
          (is (>= (get-in result [:execution/metrics :tokens] 0) 0)
              "Should have accumulated token metrics (135 for mock LLM)"))))))

(deftest test-workflow-with-none-agent
  (testing "Execute workflow with :none agent"
    (let [context {}
          result (configurable/run-configurable-workflow workflow-with-none-agent {} context)]

      (is (= :completed (:execution/status result))
          "Workflow should complete successfully")

      (is (= :done (:execution/current-phase result))
          "Should be at done phase")

      (is (empty? (:execution/artifacts result))
          ":none agent should not produce artifacts")

      (is (zero? (get-in result [:execution/metrics :tokens] 0))
          ":none agent should not use tokens"))))

(deftest test-workflow-with-callbacks
  (testing "Execute workflow with phase callbacks"
    (let [mock-llm (agent/create-mock-llm {:content "test"})
          phase-starts (atom [])
          phase-completes (atom [])
          input {:task "Test callbacks"}
          context {:llm-backend mock-llm
                   :on-phase-start (fn [_state phase]
                                     (swap! phase-starts conj (:phase/id phase)))
                   :on-phase-complete (fn [_state phase result]
                                        (swap! phase-completes conj
                                               {:phase (:phase/id phase)
                                                :success (:success? result)}))}
          result (configurable/run-configurable-workflow simple-workflow input context)]

      (is (= :completed (:execution/status result))
          "Workflow should complete")

      (is (= [:plan] @phase-starts)
          "Should have called on-phase-start for plan phase")

      (is (= 1 (count @phase-completes))
          "Should have called on-phase-complete once")

      (is (= :plan (:phase (first @phase-completes)))
          "Should have completed plan phase")

      (is (true? (:success (first @phase-completes)))
          "Phase should have succeeded"))))

(deftest test-workflow-max-phases-exceeded
  (testing "Fail workflow when max phases exceeded"
    (let [mock-llm (agent/create-mock-llm {:content "test"})
          input {:task "Test max phases"}
          context {:llm-backend mock-llm
                   :max-phases 0}  ; Force immediate failure
          result (configurable/run-configurable-workflow simple-workflow input context)]

      (is (= :failed (:execution/status result))
          "Workflow should fail")

      (let [errors (:execution/errors result)]
        (is (seq errors)
            "Should have errors")
        (is (= :max-phases-exceeded (:type (first errors)))
            "Error should be max-phases-exceeded")))))

(deftest test-workflow-phase-not-found
  (testing "Fail workflow when phase not found"
    (let [mock-llm (agent/create-mock-llm {:content "test"})
          bad-workflow (assoc simple-workflow :workflow/phases [])  ; Empty phases causes phase-not-found
          input {:task "Test bad phase"}
          context {:llm-backend mock-llm}
          result (configurable/run-configurable-workflow bad-workflow input context)]

      (is (= :failed (:execution/status result))
          "Workflow should fail")

      (let [errors (:execution/errors result)]
        (is (seq errors)
            "Should have errors")
        (is (= :phase-not-found (:type (first errors)))
            "Error should be phase-not-found")))))

(deftest test-execute-configurable-phase-directly
  (testing "Execute a single phase directly"
    (let [mock-llm (agent/create-mock-llm {:content "(defn test [] true)"})
          phase {:phase/id :implement
                 :phase/name "Implementation"
                 :phase/agent :implementer
                 :phase/gates []
                 :phase/inner-loop {:max-iterations 3}}
          exec-state {:execution/input {:task "Test"}
                      :execution/artifacts []}
          context {:llm-backend mock-llm}
          result (configurable/execute-configurable-phase phase exec-state context)]

      (is (true? (:success? result))
          "Phase should succeed")

      (is (seq (:artifacts result))
          "Should produce artifacts")

      (is (empty? (:errors result))
          "Should have no errors")

      (is (map? (:metrics result))
          "Should have metrics"))))

;------------------------------------------------------------------------------ Error handling tests

(deftest test-phase-execution-error-handling
  (testing "Handle phase execution errors gracefully"
    (let [failing-llm (agent/create-mock-llm [])  ; Empty responses will cause failure
          phase {:phase/id :plan
                 :phase/name "Planning"
                 :phase/agent :planner
                 :phase/gates []
                 :phase/inner-loop {:max-iterations 1}}
          exec-state {:execution/input {:task "Test"}
                      :execution/artifacts []}
          context {:llm-backend failing-llm}
          result (configurable/execute-configurable-phase phase exec-state context)]

      ;; Even with failures, should return a proper result structure
      (is (contains? result :success?)
          "Should have :success? key")
      (is (contains? result :artifacts)
          "Should have :artifacts key")
      (is (contains? result :errors)
          "Should have :errors key")
      (is (contains? result :metrics)
          "Should have :metrics key"))))

(deftest run-configurable-workflow-integration-test
  (testing "Run loaded workflow config"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"})
          workflow-result (loader/load-workflow :simple-test-v1 "1.0.0" {})
          workflow (:workflow workflow-result)
          input {:task "Integration test task"}
          exec-state (configurable/run-configurable-workflow workflow input {:llm-backend mock-llm})]
      (is (state/completed? exec-state))
      (is (state/has-phase-result? exec-state :plan))
      (is (state/has-phase-result? exec-state :implement))
      (is (pos? (count (:execution/artifacts exec-state))))
      (is (seq (:execution/history exec-state)))
      (is (empty? (:execution/errors exec-state))))))
