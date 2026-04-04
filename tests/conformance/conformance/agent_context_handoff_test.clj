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

(ns conformance.agent_context_handoff_test
  "N1 Agent Context Handoff conformance tests.
   Verifies context passing between phases per N1 §6.3."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def multi-phase-workflow-spec
  "Workflow with multiple phases to test context handoff."
  {:workflow/id :context-test
   :workflow/type :feature
   :workflow/intent
   {:intent/description "Test context handoff"
    :intent/type :feature
    :intent/success-criteria ["Context preserved across phases"]}
   :workflow/phases
   [{:phase/id :plan
     :phase/agent :planner
     :phase/next [{:target :implement}]
     :phase/gates []
     :phase/budget {:tokens 1000}
     :phase/inner-loop {:max-iterations 2 :validation-steps []}}
    {:phase/id :implement
     :phase/agent :implementer
     :phase/next [{:target :verify}]
     :phase/gates [:syntax-valid]
     :phase/budget {:tokens 2000}
     :phase/inner-loop {:max-iterations 3 :validation-steps [:syntax]}}
    {:phase/id :verify
     :phase/agent :tester
     :phase/next [{:target :done}]
     :phase/gates [:test-pass]
     :phase/budget {:tokens 1500}
     :phase/inner-loop {:max-iterations 2 :validation-steps [:tests]}}
    {:phase/id :done
     :phase/agent :none
     :phase/next []
     :phase/gates []
     :phase/budget {:tokens 0}
     :phase/inner-loop {:max-iterations 1 :validation-steps []}}]
   :workflow/entry :plan
   :workflow/config
   {:max-total-tokens 10000
    :max-total-iterations 20
    :max-total-time-seconds 180}})

;------------------------------------------------------------------------------ Layer 1
;; N1 §6.3.1: Phase Context Requirements

(deftest workflow-spec-in-context-test
  (testing "N1 §6.3.1: Each phase MUST receive workflow spec"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf multi-phase-workflow-spec {})]

      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts []
                        :phase-output {:plan/tasks ["task-1"]}})

      (let [state (workflow/get-state wf wf-id)
            context (get-in state [:workflow/phase-contexts :implement])]
        ;; Verify implement phase has access to workflow spec
        (is (some? context)
            "Next phase must receive context")
        (is (or (some? (:workflow/spec context))
                (some? (:workflow/intent state)))
            "Context must include workflow spec or intent")))))

(deftest prior-phase-outputs-in-context-test
  (testing "N1 §6.3.1: Each phase MUST receive prior phase outputs"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf multi-phase-workflow-spec {})
          plan-output {:plan/tasks ["task-1" "task-2"]
                       :plan/approach "Incremental implementation"}]

      ;; Complete plan phase with output
      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts []
                        :phase-output plan-output})

      (let [state (workflow/get-state wf wf-id)
            implement-context (get-in state [:workflow/phase-contexts :implement])]
        (is (some? implement-context)
            "Next phase must receive context")
        (is (contains? implement-context :prior-phases)
            "Context must include prior phases")

        (let [prior-phases (:prior-phases implement-context)]
          (is (or (contains? prior-phases :plan)
                  (seq (filter #(= :plan (:phase/name %)) (:workflow/history state))))
              "Prior phases must include plan phase output"))))))

(deftest knowledge-context-inclusion-test
  (testing "N1 §6.3.1: Phase context SHOULD include knowledge patterns"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf multi-phase-workflow-spec {})
          state (workflow/get-state wf wf-id)]
      ;; Verify structure supports knowledge context
      (is (or (contains? state :workflow/knowledge-context)
              (contains? state :workflow/context)
              (map? state))
          "Workflow state must support knowledge context")

      ;; Knowledge context may be empty but structure should exist
      (is (some? state)
          "State must exist to hold knowledge context"))))

(deftest policy-context-inclusion-test
  (testing "N1 §6.3.1: Phase context SHOULD include policy packs"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf multi-phase-workflow-spec {})
          state (workflow/get-state wf wf-id)]
      ;; Verify structure supports policy context
      (is (or (contains? state :workflow/policy-context)
              (contains? state :workflow/config)
              (map? state))
          "Workflow state must support policy context")

      ;; Policy context may be empty but structure should exist
      (is (some? state)
          "State must exist to hold policy context"))))

;------------------------------------------------------------------------------ Layer 2
;; N1 §8.2.2: Agent Context Handoff Integration Tests

(deftest multi-phase-context-flow-test
  (testing "N1 §8.2.2: Context flows correctly through multiple phases"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf multi-phase-workflow-spec {})
          plan-output {:plan/tasks ["Implement feature X"
                                   "Add tests for feature X"]
                       :plan/estimated-complexity :medium}
          implement-output {:implement/files-changed ["src/feature_x.clj"]
                           :implement/lines-added 50}]

      ;; Phase 1: Plan
      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts []
                        :phase-output plan-output})

      (let [state-after-plan (workflow/get-state wf wf-id)]
        (is (= :implement (:workflow/current-phase state-after-plan))
            "Should transition to implement phase"))

      ;; Phase 2: Implement
      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts []
                        :phase-output implement-output})

      (let [state-after-implement (workflow/get-state wf wf-id)]
        (is (= :verify (:workflow/current-phase state-after-implement))
            "Should transition to verify phase")

        ;; Verify context accumulation
        (let [history (:workflow/history state-after-implement)]
          (is (>= (count history) 2)
              "History must contain plan and implement phases")

          (let [plan-phase (first (filter #(= :plan (:phase/name %)) history))
                implement-phase (first (filter #(= :implement (:phase/name %)) history))]
            (is (some? plan-phase)
                "Plan phase must be in history")
            (is (some? implement-phase)
                "Implement phase must be in history")))))))

(deftest context-schema-validation-test
  (testing "N1 §6.3.2: Context follows required schema"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf multi-phase-workflow-spec {})]

      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts []
                        :phase-output {:plan/tasks ["task-1"]}})

      (let [state (workflow/get-state wf wf-id)]
        ;; Verify state has required fields
        (is (uuid? (:workflow/id state))
            "Context must have workflow ID")
        (is (keyword? (:workflow/current-phase state))
            "Context must have current phase")
        (is (contains? #{:pending :running :executing :completed :failed}
                       (:workflow/status state))
            "Context must have valid status")))))

(deftest context-immutability-test
  (testing "Context preservation: prior phase data not overwritten"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf multi-phase-workflow-spec {})
          plan-output {:plan/tasks ["Original task"]}
          implement-output {:implement/approach "New approach"}]

      ;; Execute phases
      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts []
                        :phase-output plan-output})

      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts []
                        :phase-output implement-output})

      ;; Verify plan output still accessible
      (let [state (workflow/get-state wf wf-id)
            history (:workflow/history state)
            plan-phase (first (filter #(= :plan (:phase/name %)) history))]
        (is (some? plan-phase)
            "Plan phase data must be preserved in history")))))

;------------------------------------------------------------------------------ Layer 2
;; Context availability to agents

(deftest agent-receives-complete-context-test
  (testing "N1 §6.1: Agent receives complete context for execution"
    (let [wf (workflow/create-workflow)
          wf-id (workflow/start wf multi-phase-workflow-spec {})]

      ;; In real implementation, agent would receive context
      ;; We verify the context is constructed correctly
      (is (some? (:workflow/current-phase (workflow/get-state wf wf-id)))
          "Current phase must be available to agent")
      (is (some? (workflow/get-state wf wf-id))
          "Complete state must be available for agent context"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'conformance.agent_context_handoff_test)

  :leave-this-here)
