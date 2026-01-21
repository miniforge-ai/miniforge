;; Copyright 2025 miniforge.ai
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
   [ai.miniforge.workflow.configurable :as configurable]
   [ai.miniforge.workflow.loader :as loader]
   [ai.miniforge.workflow.state :as state]))

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

(deftest select-next-phase-test
  (testing "Select next phase on success"
    (let [workflow {:workflow/phases
                    [{:phase/id :plan
                      :phase/name "Plan"
                      :phase/on-success {:transition/target :implement}
                      :phase/on-failure {:transition/target :plan}}]
                    :workflow/exit-phases [:done]}
          exec-state {:execution/current-phase :plan}
          phase-result {:success? true}
          next-phase (configurable/select-next-phase workflow exec-state phase-result)]
      (is (= :implement next-phase)
          "Should transition to success target")))

  (testing "Select next phase on failure"
    (let [workflow {:workflow/phases
                    [{:phase/id :plan
                      :phase/name "Plan"
                      :phase/on-success {:transition/target :implement}
                      :phase/on-failure {:transition/target :plan}}]
                    :workflow/exit-phases [:done]}
          exec-state {:execution/current-phase :plan}
          phase-result {:success? false}
          next-phase (configurable/select-next-phase workflow exec-state phase-result)]
      (is (= :plan next-phase)
          "Should transition to failure target")))

  (testing "Select :done when at exit phase"
    (let [workflow {:workflow/phases
                    [{:phase/id :done
                      :phase/name "Done"}]
                    :workflow/exit-phases [:done]}
          exec-state {:execution/current-phase :done}
          phase-result {:success? true}
          next-phase (configurable/select-next-phase workflow exec-state phase-result)]
      (is (= :done next-phase)
          "Should select :done when at exit phase")))

  (testing "Select :done when no transition defined"
    (let [workflow {:workflow/phases
                    [{:phase/id :orphan
                      :phase/name "Orphan"}]
                    :workflow/exit-phases [:done]}
          exec-state {:execution/current-phase :orphan}
          phase-result {:success? true}
          next-phase (configurable/select-next-phase workflow exec-state phase-result)]
      (is (= :done next-phase)
          "Should select :done when no transition defined"))))

(deftest execute-configurable-phase-test
  (testing "Execute normal phase returns success (stub)"
    (let [phase {:phase/id :plan
                 :phase/name "Plan"
                 :phase/agent-type :planner
                 :phase/gates []}
          exec-state {}
          context {}
          result (configurable/execute-configurable-phase phase exec-state context)]
      (is (true? (:success? result))
          "Stub should return success")
      (is (vector? (:artifacts result))
          "Should return artifacts vector")
      (is (seq (:artifacts result))
          "Should return at least one artifact")
      (is (vector? (:errors result))
          "Should return errors vector")
      (is (empty? (:errors result))
          "Stub should have no errors")
      (is (map? (:metrics result))
          "Should return metrics map")
      (is (pos? (:tokens (:metrics result)))
          "Should have token count")))

  (testing "Execute done phase returns success with no artifacts"
    (let [phase {:phase/id :done
                 :phase/name "Done"
                 :phase/agent-type :none}
          exec-state {}
          context {}
          result (configurable/execute-configurable-phase phase exec-state context)]
      (is (true? (:success? result))
          "Done phase should succeed")
      (is (empty? (:artifacts result))
          "Done phase should have no artifacts")
      (is (= 0 (:tokens (:metrics result)))
          "Done phase should have zero tokens"))))

(deftest run-configurable-workflow-test
  (testing "Run simple workflow to completion"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :start
                      :phase/name "Start"
                      :phase/agent-type :planner
                      :phase/on-success {:transition/target :end}}
                     {:phase/id :end
                      :phase/name "End"
                      :phase/agent-type :none}]
                    :workflow/entry-phase :start
                    :workflow/exit-phases [:end]}
          input {:task "Test"}
          exec-state (configurable/run-configurable-workflow workflow input {})]
      (is (state/completed? exec-state)
          "Workflow should complete")
      (is (= 2 (count (:execution/phase-results exec-state)))
          "Should have results for both phases")
      (is (pos? (count (:execution/artifacts exec-state)))
          "Should have at least one artifact")
      (is (pos? (:tokens (:execution/metrics exec-state)))
          "Should have accumulated tokens")))

  (testing "Run workflow with callbacks"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :start
                      :phase/name "Start"
                      :phase/agent-type :planner
                      :phase/on-success {:transition/target :end}}
                     {:phase/id :end
                      :phase/name "End"
                      :phase/agent-type :none}]
                    :workflow/entry-phase :start
                    :workflow/exit-phases [:end]}
          input {:task "Test"}
          phase-starts (atom [])
          phase-completes (atom [])
          context {:on-phase-start (fn [_state phase]
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
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :loop
                      :phase/name "Loop"
                      :phase/agent-type :planner
                      :phase/on-success {:transition/target :loop}
                      :phase/on-failure {:transition/target :loop}}]  ; Infinite loop
                    :workflow/entry-phase :loop
                    :workflow/exit-phases [:done]}
          input {:task "Test"}
          context {:max-phases 5}
          exec-state (configurable/run-configurable-workflow workflow input context)]
      (is (state/failed? exec-state)
          "Workflow should fail due to max phases")
      (is (some #(= :max-phases-exceeded (:type %))
                (:execution/errors exec-state))
          "Should have max-phases-exceeded error")))

  (testing "Run workflow fails on non-existent phase"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/phases []  ; No phases defined
                    :workflow/entry-phase :missing
                    :workflow/exit-phases [:done]}
          input {:task "Test"}
          exec-state (configurable/run-configurable-workflow workflow input {})]
      (is (state/failed? exec-state)
          "Workflow should fail when phase not found")
      (is (some #(= :phase-not-found (:type %))
                (:execution/errors exec-state))
          "Should have phase-not-found error"))))

(deftest run-configurable-workflow-integration-test
  (testing "Run loaded workflow config"
    ;; Load actual workflow from resources
    (let [workflow-result (loader/load-workflow :simple-test-v1 "1.0.0" {})
          workflow (:workflow workflow-result)
          input {:task "Integration test task"}
          exec-state (configurable/run-configurable-workflow workflow input {})]

      ;; Verify execution completed
      (is (state/completed? exec-state)
          "Workflow should complete successfully")
      (is (= :done (:execution/current-phase exec-state))
          "Should end at done phase")

      ;; Verify phase results
      (is (state/has-phase-result? exec-state :plan)
          "Should have plan phase result")
      (is (state/has-phase-result? exec-state :implement)
          "Should have implement phase result")
      (is (state/has-phase-result? exec-state :done)
          "Should have done phase result")

      ;; Verify artifacts produced
      (is (pos? (count (:execution/artifacts exec-state)))
          "Should have produced artifacts")

      ;; Verify metrics accumulated
      (let [metrics (:execution/metrics exec-state)]
        (is (pos? (:tokens metrics))
            "Should have accumulated tokens")
        (is (>= (:cost-usd metrics) 0)
            "Should have accumulated cost")
        (is (>= (:duration-ms metrics) 0)
            "Should have accumulated duration"))

      ;; Verify history tracked
      (is (seq (:execution/history exec-state))
          "Should have transition history")

      ;; Verify no errors
      (is (empty? (:execution/errors exec-state))
          "Should have no errors"))))

(deftest workflow-execution-flow-test
  (testing "Complete workflow execution with phase transitions"
    (let [workflow {:workflow/id :full-test
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :plan
                      :phase/name "Plan"
                      :phase/agent-type :planner
                      :phase/on-success {:transition/target :implement}}
                     {:phase/id :implement
                      :phase/name "Implement"
                      :phase/agent-type :implementer
                      :phase/on-success {:transition/target :verify}}
                     {:phase/id :verify
                      :phase/name "Verify"
                      :phase/agent-type :tester
                      :phase/on-success {:transition/target :done}}
                     {:phase/id :done
                      :phase/name "Done"
                      :phase/agent-type :none}]
                    :workflow/entry-phase :plan
                    :workflow/exit-phases [:done]}
          input {:task "Full workflow test"}
          exec-state (configurable/run-configurable-workflow workflow input {})]

      ;; Verify workflow completed all phases
      (is (state/completed? exec-state)
          "Workflow should complete")
      (is (= 4 (count (:execution/phase-results exec-state)))
          "Should have results for all 4 phases")

      ;; Verify transition history
      (let [history (:execution/history exec-state)]
        (is (= 3 (count history))
            "Should have 3 transitions (plan->impl->verify->done)")
        (is (= :plan (:from (first history)))
            "First transition from plan")
        (is (= :implement (:to (first history)))
            "First transition to implement")))))
