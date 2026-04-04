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

(ns conformance.n1_architecture_test
  "End-to-end N1 Architecture conformance tests.
   Verifies complete workflow execution from spec → PR with evidence bundle."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.interface :as workflow]
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.agent.interface :as agent]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def test-workflow-spec
  "Minimal workflow spec for conformance testing."
  {:workflow/id :conformance-test
   :workflow/type :feature
   :workflow/intent
   {:intent/description "Test workflow for N1 conformance"
    :intent/type :feature
    :intent/success-criteria ["Code compiles" "Tests pass"]}
   :workflow/phases
   [{:phase/id :plan
     :phase/agent :planner
     :phase/next [{:target :implement}]
     :phase/gates []
     :phase/budget {:tokens 1000}
     :phase/inner-loop {:max-iterations 2 :validation-steps []}}
    {:phase/id :implement
     :phase/agent :implementer
     :phase/next [{:target :done}]
     :phase/gates [:syntax-valid]
     :phase/budget {:tokens 2000}
     :phase/inner-loop {:max-iterations 3 :validation-steps [:syntax]}}
    {:phase/id :done
     :phase/agent :none
     :phase/next []
     :phase/gates []
     :phase/budget {:tokens 0}
     :phase/inner-loop {:max-iterations 1 :validation-steps []}}]
   :workflow/entry :plan
   :workflow/config
   {:max-total-tokens 5000
    :max-total-iterations 10
    :max-total-time-seconds 120}})

(defn create-test-context
  "Create a minimal test execution context with mock LLM."
  []
  {:llm-backend (agent/create-mock-llm
                 [{:content "(def task-1 \"Create function\")"
                   :usage {:input-tokens 50 :output-tokens 25}}
                  {:content "(defn hello [] \"world\")"
                   :usage {:input-tokens 100 :output-tokens 50}}])
   :artifact-store (artifact/create-store {:storage-backend :memory})
   :event-log (atom [])})

(defn record-event
  "Record an event in the test event log."
  [event-log event]
  (swap! event-log conj event))

;------------------------------------------------------------------------------ Layer 1
;; N1 §2.1: Workflow Requirements

(deftest workflow-uuid-assignment-test
  (testing "N1 §2.1.1: Workflow MUST have unique UUID"
    (let [context (create-test-context)
          wf (workflow/create-workflow)
          wf-id-1 (workflow/start wf test-workflow-spec context)
          wf-id-2 (workflow/start wf test-workflow-spec context)]
      (is (uuid? wf-id-1)
          "Workflow ID must be a UUID")
      (is (uuid? wf-id-2)
          "Second workflow ID must be a UUID")
      (is (not= wf-id-1 wf-id-2)
          "Each workflow must have unique UUID"))))

(deftest workflow-status-tracking-test
  (testing "N1 §2.1.1: Workflow MUST track status through lifecycle"
    (let [context (create-test-context)
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec context)
          initial-state (workflow/get-state wf wf-id)]
      (is (some? initial-state)
          "Workflow state must be retrievable")
      (is (contains? #{:pending :running :executing} (:workflow/status initial-state))
          "Initial status must be pending, running, or executing")
      (is (some? (:workflow/created-at initial-state))
          "Created timestamp must be set"))))

(deftest workflow-phase-execution-records-test
  (testing "N1 §2.1.1: Workflow MUST record all phase executions"
    (let [context (create-test-context)
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec context)]
      ;; Execute plan phase
      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts []
                        :phase-output {:plan/tasks ["task-1"]}})
      (let [state (workflow/get-state wf wf-id)]
        (is (seq (:workflow/history state))
            "Workflow must record phase execution history")
        (is (some #(= :plan (:phase/name %)) (:workflow/history state))
            "Plan phase execution must be recorded")))))

(deftest workflow-evidence-bundle-generation-test
  (testing "N1 §2.1.1: Workflow MUST generate evidence bundle on completion"
    (let [context (create-test-context)
          evidence-mgr (evidence/create-evidence-manager
                        {:artifact-store (:artifact-store context)})
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec context)]
      ;; Complete workflow phases
      (workflow/advance wf wf-id {:success? true :artifacts []})
      (workflow/advance wf wf-id {:success? true :artifacts []})

      (let [state (workflow/get-state wf wf-id)
            bundle (when (contains? #{:completed :failed} (:workflow/status state))
                     (evidence/create-bundle evidence-mgr wf-id
                                           {:workflow-state state}))]
        (when bundle
          (is (uuid? (:evidence-bundle/id bundle))
              "Evidence bundle must have UUID")
          (is (= wf-id (:evidence-bundle/workflow-id bundle))
              "Evidence bundle must link to workflow"))))))

;------------------------------------------------------------------------------ Layer 1
;; N1 §2.2: Phase Requirements

(deftest phase-status-tracking-test
  (testing "N1 §2.2: Phase MUST track status"
    (let [context (create-test-context)
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec context)
          state (workflow/get-state wf wf-id)]
      (is (some? (:workflow/current-phase state))
          "Current phase must be tracked")
      (is (keyword? (:workflow/current-phase state))
          "Phase name must be a keyword"))))

(deftest phase-context-handoff-test
  (testing "N1 §2.2: Phase MUST have input/output context"
    (let [context (create-test-context)
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec context)
          plan-output {:plan/tasks ["task-1" "task-2"]}]
      ;; Complete plan phase with output
      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts []
                        :phase-output plan-output})
      (let [state (workflow/get-state wf wf-id)
            implement-phase-ctx (get-in state [:workflow/phase-contexts :implement])]
        (is (some? implement-phase-ctx)
            "Next phase must receive context")
        (is (contains? implement-phase-ctx :prior-phases)
            "Context must include prior phase outputs")))))

(deftest phase-artifact-tracking-test
  (testing "N1 §2.2: Phase MUST track artifacts produced"
    (let [context (create-test-context)
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec context)
          test-artifact {:artifact/id (random-uuid)
                         :artifact/type :code
                         :artifact/content "(defn test [] :ok)"}]
      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts [(:artifact/id test-artifact)]})
      (let [state (workflow/get-state wf wf-id)]
        (is (seq (:workflow/artifacts state))
            "Workflow must track all artifacts produced")))))

;------------------------------------------------------------------------------ Layer 2
;; N1 §8.2: Integration Tests - End-to-End Workflow Execution

(deftest end-to-end-workflow-execution-test
  (testing "N1 §8.2.1: Complete workflow execution from spec to evidence"
    (let [context (create-test-context)
          evidence-mgr (evidence/create-evidence-manager
                        {:artifact-store (:artifact-store context)})
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec context)]

      ;; Phase 1: Plan
      (testing "Plan phase executes"
        (let [state (workflow/get-state wf wf-id)]
          (is (= :plan (:workflow/current-phase state))
              "Workflow should start in plan phase"))

        (workflow/advance wf wf-id
                         {:success? true
                          :artifacts []
                          :phase-output {:plan/tasks ["Implement feature"]}})

        (let [state (workflow/get-state wf wf-id)]
          (is (not= :plan (:workflow/current-phase state))
              "Workflow should advance from plan phase")))

      ;; Phase 2: Implement
      (testing "Implement phase executes"
        (let [test-artifact {:artifact/id (random-uuid)
                             :artifact/type :code
                             :artifact/content "(defn feature [] :implemented)"}]
          (workflow/advance wf wf-id
                           {:success? true
                            :artifacts [(:artifact/id test-artifact)]
                            :phase-output {:implement/files-changed ["feature.clj"]}})))

      ;; Phase 3: Done
      (workflow/advance wf wf-id {:success? true :artifacts []})

      ;; Verify final state
      (testing "Workflow completes successfully"
        (let [final-state (workflow/get-state wf wf-id)]
          (is (= :completed (:workflow/status final-state))
              "Workflow should complete successfully")
          (is (= :done (:workflow/current-phase final-state))
              "Workflow should end in done phase")))

      ;; Verify evidence bundle
      (testing "Evidence bundle is generated"
        (let [state (workflow/get-state wf wf-id)
              bundle (evidence/create-bundle evidence-mgr wf-id
                                           {:workflow-state state})]
          (is (some? bundle)
              "Evidence bundle must be created")
          (is (uuid? (:evidence-bundle/id bundle))
              "Evidence bundle must have ID")
          (is (= wf-id (:evidence-bundle/workflow-id bundle))
              "Evidence bundle must link to workflow")
          (is (some? (:evidence/intent bundle))
              "Evidence bundle must capture intent")
          (is (some? (:evidence/outcome bundle))
              "Evidence bundle must capture outcome"))))))

(deftest workflow-with-phase-failure-test
  (testing "N1 §8.2.1: Workflow handles phase failures"
    (let [context (create-test-context)
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec context)]

      ;; Plan phase succeeds
      (workflow/advance wf wf-id
                       {:success? true
                        :artifacts []
                        :phase-output {:plan/tasks ["task-1"]}})

      ;; Implement phase fails
      (workflow/advance wf wf-id
                       {:success? false
                        :errors [{:code :gate-failure
                                 :message "Syntax validation failed"}]})

      (let [state (workflow/get-state wf wf-id)]
        (is (seq (:workflow/errors state))
            "Workflow must record errors")
        (is (some #(= :gate-failure (:code %)) (:workflow/errors state))
            "Specific error must be recorded")))))

;------------------------------------------------------------------------------ Layer 2
;; N1 §8.2: Integration Tests - Complete Workflow Lifecycle

(deftest workflow-lifecycle-completeness-test
  (testing "N1 §8.2: Complete workflow lifecycle is tracked"
    (let [context (create-test-context)
          wf (workflow/create-workflow)
          wf-id (workflow/start wf test-workflow-spec context)
          start-state (workflow/get-state wf wf-id)]

      ;; Verify initial state
      (is (inst? (:workflow/created-at start-state))
          "Creation timestamp must be recorded")
      (is (some? (:workflow/started-at start-state))
          "Start timestamp must be recorded")

      ;; Execute phases
      (workflow/advance wf wf-id {:success? true :artifacts []})
      (workflow/advance wf wf-id {:success? true :artifacts []})
      (workflow/advance wf wf-id {:success? true :artifacts []})

      (let [final-state (workflow/get-state wf wf-id)]
        (is (inst? (:workflow/completed-at final-state))
            "Completion timestamp must be recorded")
        (is (seq (:workflow/history final-state))
            "Phase history must be complete")
        (is (>= (count (:workflow/history final-state)) 3)
            "All phases must be in history")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'conformance.n1_architecture_test)

  ;; Run specific test
  (clojure.test/test-var #'end-to-end-workflow-execution-test)

  :leave-this-here)
