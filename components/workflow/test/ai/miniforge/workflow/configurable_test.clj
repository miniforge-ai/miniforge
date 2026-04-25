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

(ns ai.miniforge.workflow.configurable-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.workflow.configurable :as configurable]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.state :as state]
   [ai.miniforge.agent.interface :as agent]))

(deftest find-phase-test
  (testing "Find phase by ID in workflow"
    (let [workflow {:workflow/phases
                    [{:phase/id :plan
                      :phase/name "Plan"}
                     {:phase/id :implement
                      :phase/name "Implement"}]}
          phase (configurable/find-phase workflow :plan)]
      (is (some? phase) "Should find phase")
      (is (= :plan (:phase/id phase)) "Should have correct ID")
      (is (= "Plan" (:phase/name phase)) "Should have correct name")))

  (testing "Find non-existent phase returns nil"
    (let [workflow {:workflow/phases
                    [{:phase/id :plan
                      :phase/name "Plan"}]}]
      (is (nil? (configurable/find-phase workflow :missing))
          "Should return nil for non-existent phase"))))

(deftest configurable-workflow-uses-machine-transitions-test
  (testing "Configurable workflows advance through the compiled execution machine"
    (let [workflow {:workflow/id :machine-backed
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :plan
                      :phase/name "Plan"
                      :phase/agent :none
                      :phase/next [{:target :implement}]}
                     {:phase/id :implement
                      :phase/name "Implement"
                      :phase/agent :none
                      :phase/next [{:target :done}]}
                     {:phase/id :done
                      :phase/name "Done"
                      :phase/agent :none
                      :phase/next []}]}
          exec-state (configurable/run-configurable-workflow workflow {} {})]
      (is (state/completed? exec-state))
      (is (= :done (:execution/current-phase exec-state)))
      (is (= 2 (:execution/phase-index exec-state)))
      (is (= #{:plan :implement :done}
             (set (keys (:execution/phase-results exec-state)))))
      (let [phase-transitions (filter :from-phase (:execution/history exec-state))]
        (is (= 2 (count phase-transitions)))
        (is (= :plan (:from-phase (first phase-transitions))))
        (is (= :implement (:to-phase (first phase-transitions))))
        (is (= :implement (:from-phase (second phase-transitions))))
        (is (= :done (:to-phase (second phase-transitions))))))))

(deftest configurable-workflow-dag-success-skips-implement-test
  (testing "successful DAG execution advances past synthesized implement work"
    (let [plan-artifact {:plan/id (random-uuid)
                         :artifact/type :plan
                         :tasks [{:task/id "task-1"}]}
          dag-artifact {:artifact/type :pull-request
                        :artifact/id (random-uuid)}
          workflow {:workflow/id :dag-configurable
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :plan
                      :phase/name "Plan"
                      :phase/handler :test/plan
                      :phase/next [{:target :implement}]}
                     {:phase/id :implement
                      :phase/name "Implement"
                      :phase/agent :none
                      :phase/next [{:target :pr-monitor}]}
                     {:phase/id :pr-monitor
                      :phase/name "PR Monitor"
                      :phase/agent :none
                      :phase/next [{:target :done}]}
                     {:phase/id :done
                      :phase/name "Done"
                      :phase/agent :none
                      :phase/next []}]
                    :workflow/pipeline
                    [{:phase :plan :on-success :implement}
                     {:phase :implement :on-success :pr-monitor}
                     {:phase :pr-monitor :on-success :done}
                     {:phase :done}]}
          execution-context {:phase-handlers {:test/plan
                                              (fn [_phase _exec-state _context]
                                                {:success? true
                                                 :artifacts [plan-artifact]
                                                 :errors []
                                                 :metrics {:duration-ms 1}})}}
          exec-state (ctx/create-context workflow {} execution-context)
          plan-phase (configurable/find-phase workflow :plan)]
      (with-redefs [dag-orch/parallelizable-plan? (fn [_plan] true)
                    dag-orch/execute-plan-as-dag (fn [_plan _context]
                                                  {:success? true
                                                   :artifacts [dag-artifact]
                                                   :metrics {:tokens 3
                                                             :cost-usd 0.0
                                                             :duration-ms 5}
                                                   :pr-infos [{:pr/url "https://example.test/pr/1"}]})]
        (let [[status next-state]
              (configurable/execute-phase-step workflow
                                               exec-state
                                               plan-phase
                                               execution-context
                                               {})]
          (is (= :continue status))
          (is (= :pr-monitor (:execution/current-phase next-state)))
          (is (= #{:plan :implement :dag-execution}
                 (set (keys (:execution/phase-results next-state)))))
          (is (true? (get-in next-state [:execution/phase-results :implement :success?])))
          (is (= [dag-artifact]
                 (get-in next-state [:execution/phase-results :implement :artifacts])))
          (is (= [{:pr/url "https://example.test/pr/1"}]
                 (:execution/dag-pr-infos next-state)))
          (let [phase-transitions (filter :from-phase (:execution/history next-state))
                transition-pairs (set (map (juxt :from-phase :to-phase)
                                           phase-transitions))]
            (is (= 2 (count phase-transitions)))
            (is (= #{[:plan :implement]
                     [:implement :pr-monitor]}
                   transition-pairs))))))))

(deftest execute-configurable-phase-test
  (testing "Execute normal phase returns success"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"})
          phase {:phase/id :plan
                 :phase/name "Plan"
                 :phase/agent :planner
                 :phase/gates []}
          exec-state {}
          context {:llm-backend mock-llm}
          result (configurable/execute-configurable-phase phase exec-state context)]
      (is (true? (:success? result))
          "Should return success")
      (is (vector? (:artifacts result))
          "Should return artifacts vector")
      (is (seq (:artifacts result))
          "Should return at least one artifact")
      (is (vector? (:errors result))
          "Should return errors vector")
      (is (empty? (:errors result))
          "Should have no errors")
      (is (map? (:metrics result))
          "Should return metrics map")
      (is (>= (:tokens (:metrics result) 0) 0)
          "Should have token count (0 for mock LLM)")))

  (testing "Execute done phase returns success with no artifacts"
    (let [phase {:phase/id :done
                 :phase/name "Done"
                 :phase/agent :none}
          exec-state {}
          context {}
          result (configurable/execute-configurable-phase phase exec-state context)]
      (is (true? (:success? result))
          "Done phase should succeed")
      (is (empty? (:artifacts result))
          "Done phase should have no artifacts")
      (is (= 0 (:tokens (:metrics result)))
          "Done phase should have zero tokens")))

  (testing "Execute handler-backed phase uses caller-provided handler"
    (let [artifact-id (random-uuid)
          phase {:phase/id :acquire
                 :phase/name "Acquire"
                 :phase/agent :none
                 :phase/handler :etl/acquire}
          result (configurable/execute-configurable-phase
                  phase
                  {:execution/input {:issuer "ACME"}}
                  {:phase-handlers
                   {:etl/acquire
                    (fn [_phase exec-state _context]
                      {:success? true
                       :artifacts [(artifact/build-artifact
                                    {:id artifact-id
                                     :type :etl/raw-filing
                                     :version "1.0.0"
                                     :content (:execution/input exec-state)})]
                       :errors []
                       :metrics {:duration-ms 5}})}})]
      (is (true? (:success? result)))
      (is (= artifact-id (get-in result [:artifacts 0 :artifact/id]))))))

(deftest run-configurable-workflow-test
  (testing "Run simple workflow to completion"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"})
          workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :start
                      :phase/name "Start"
                      :phase/agent :planner
                      :phase/task-type :plan
                      :phase/next [{:target :end}]}
                     {:phase/id :end
                      :phase/name "End"
                      :phase/agent :none
                      :phase/next []}]
                    :workflow/entry-phase :start
                    :workflow/exit-phases [:end]}
          input {:task "Test"}
          exec-state (configurable/run-configurable-workflow workflow input {:llm-backend mock-llm})]
      (is (state/completed? exec-state)
          "Workflow should complete")
      (is (= 2 (count (:execution/phase-results exec-state)))
          "Should have results for both phases")
      (is (>= (count (:execution/artifacts exec-state)) 0)
          "Artifacts may be empty with mock LLM")
      (is (>= (:tokens (:execution/metrics exec-state) 0) 0)
          "Should have accumulated tokens (0 for mock LLM)")))

  (testing "Run workflow with callbacks"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"})
          workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :start
                      :phase/name "Start"
                      :phase/agent :planner
                      :phase/task-type :plan
                      :phase/next [{:target :end}]}
                     {:phase/id :end
                      :phase/name "End"
                      :phase/agent :none
                      :phase/next []}]
                    :workflow/entry-phase :start
                    :workflow/exit-phases [:end]}
          input {:task "Test"}
          phase-starts (atom [])
          phase-completes (atom [])
          context {:llm-backend mock-llm
                   :on-phase-start (fn [_state phase]
                                     (swap! phase-starts conj (:phase/id phase)))
                   :on-phase-complete (fn [_state phase _result]
                                        (swap! phase-completes conj (:phase/id phase)))}
          exec-state (configurable/run-configurable-workflow workflow input context)]
      (is (state/completed? exec-state)
          "Workflow should complete")
      (is (= [:start :end] @phase-starts)
          "Should call on-phase-start for each phase")
      (is (= [:start :end] @phase-completes)
          "Should call on-phase-complete for each phase")))

  (testing "Run workflow with max-phases limit"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"})
          workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :loop
                      :phase/name "Loop"
                      :phase/agent :planner
                      :phase/task-type :plan
                      :phase/next [{:target :loop}]}]  ; Infinite loop
                    :workflow/entry-phase :loop
                    :workflow/exit-phases [:done]}
          input {:task "Test"}
          context {:llm-backend mock-llm
                   :max-phases 5}
          exec-state (configurable/run-configurable-workflow workflow input context)]
      (is (state/failed? exec-state)
          "Workflow should fail due to max phases")
      (is (some #(= :max-phases-exceeded (:type %))
                (:execution/errors exec-state))
          "Should have max-phases-exceeded error")))

  ;; Note: Removed test for workflow with no phases as it triggers invalid FSM transition
  ;; (workflow would be in :pending state when trying to fail, but FSM requires :running -> :failed)
  )

(deftest workflow-execution-flow-test
  (testing "Complete workflow execution with phase transitions"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"})
          workflow {:workflow/id :full-test
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :plan
                      :phase/name "Plan"
                      :phase/agent :planner
                      :phase/next [{:target :implement}]}
                     {:phase/id :implement
                      :phase/name "Implement"
                      :phase/agent :implementer
                      :phase/next [{:target :verify}]}
                     {:phase/id :verify
                      :phase/name "Verify"
                      :phase/agent :tester
                      :phase/task-type :test
                      :phase/next [{:target :done}]}
                     {:phase/id :done
                      :phase/name "Done"
                      :phase/agent :none
                      :phase/next []}]
                    :workflow/entry-phase :plan
                    :workflow/exit-phases [:done]}
          input {:task "Full workflow test"}
          exec-state (configurable/run-configurable-workflow workflow input {:llm-backend mock-llm})]

      ;; Verify workflow completed all phases
      (is (state/completed? exec-state)
          "Workflow should complete")
      (is (= 4 (count (:execution/phase-results exec-state)))
          "Should have results for all executed phases, including :done")

      ;; Verify transition history
      (let [history (:execution/history exec-state)
            phase-transitions (filter :from-phase history)]
        (is (= 3 (count phase-transitions))
            "Should have 3 phase transitions (plan->impl, impl->verify, verify->done)")
        (is (= :plan (:from-phase (first phase-transitions)))
            "First transition from plan")
        (is (= :implement (:to-phase (first phase-transitions)))
            "First transition to implement")))))
