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

(ns ai.miniforge.orchestrator.interface-test
  "Tests for the orchestrator component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.orchestrator.interface :as orch]
   [ai.miniforge.orchestrator.protocol :as proto]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.llm.interface :as llm]))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn create-test-orchestrator
  "Create an orchestrator with mock dependencies."
  []
  (let [llm-client (llm/mock-client {:output "Generated response"})
        store (knowledge/create-store)
        artifact-store (artifact/create-transit-store {:dir (str (java.nio.file.Files/createTempDirectory
                                                                  "orchestrator-artifacts"
                                                                  (make-array java.nio.file.attribute.FileAttribute 0)))})]
    (orch/create-orchestrator llm-client store artifact-store)))

;; ============================================================================
;; Protocol tests
;; ============================================================================

(deftest task-router-test
  (testing "SimpleTaskRouter routes tasks correctly"
    (let [router (orch/create-router)]
      (testing "plan tasks route to planner"
        (let [task {:task/id (random-uuid) :task/type :plan}
              result (proto/route-task router task {})]
          (is (= :planner (:agent-role result)))))

      (testing "implement tasks route to implementer"
        (let [task {:task/id (random-uuid) :task/type :implement}
              result (proto/route-task router task {})]
          (is (= :implementer (:agent-role result)))))

      (testing "test tasks route to tester"
        (let [task {:task/id (random-uuid) :task/type :test}
              result (proto/route-task router task {})]
          (is (= :tester (:agent-role result)))))

      (testing "review tasks route to reviewer"
        (let [task {:task/id (random-uuid) :task/type :review}
              result (proto/route-task router task {})]
          (is (= :reviewer (:agent-role result)))))

      (testing "unknown tasks route to implementer (default)"
        (let [task {:task/id (random-uuid) :task/type :unknown}
              result (proto/route-task router task {})]
          (is (= :implementer (:agent-role result))))))))

(deftest budget-manager-test
  (testing "SimpleBudgetManager tracks usage"
    (let [budget-mgr (orch/create-budget-manager)
          workflow-id (random-uuid)]

      (testing "initial budget check uses default budget"
        (let [{:keys [within-budget? remaining]} (proto/check-budget budget-mgr workflow-id)]
          (is (true? within-budget?))
          ;; Default budget is used, so remaining is available
          (is (some? remaining))))

      (testing "can set budget"
        (proto/set-budget budget-mgr workflow-id {:max-tokens 10000
                                                   :max-cost-usd 1.0
                                                   :timeout-ms 60000})
        (let [{:keys [within-budget? remaining]} (proto/check-budget budget-mgr workflow-id)]
          (is (true? within-budget?))
          (is (= 10000 (:tokens remaining)))
          (is (= 1.0 (:cost-usd remaining)))))

      (testing "can track usage"
        (proto/track-usage budget-mgr workflow-id {:tokens 5000
                                                    :cost-usd 0.5
                                                    :duration-ms 30000})
        (let [{:keys [within-budget? remaining]} (proto/check-budget budget-mgr workflow-id)]
          (is (true? within-budget?))
          (is (= 5000 (:tokens remaining)))
          (is (= 0.5 (:cost-usd remaining)))))

      (testing "budget exceeded when over limit"
        (proto/track-usage budget-mgr workflow-id {:tokens 6000
                                                    :cost-usd 0.0
                                                    :duration-ms 0})
        (let [{:keys [within-budget?]} (proto/check-budget budget-mgr workflow-id)]
          (is (false? within-budget?)))))))

(deftest knowledge-coordinator-test
  (testing "SimpleKnowledgeCoordinator injects knowledge"
    (let [store (knowledge/create-store)
          coord (orch/create-knowledge-coordinator store)]

      ;; Add some test knowledge
      (let [zettel (knowledge/create-zettel
                    "test-rule"
                    "Always test your code"
                    :rule
                    {:tags #{:testing}
                     :dewey "400"})]
        (knowledge/put-zettel store zettel))

      (testing "inject-for-agent returns knowledge structure"
        (let [task {:task/id (random-uuid) :task/type :test}
              result (proto/inject-for-agent coord :tester task {})]
          (is (some? result))
          (is (number? (:count result)))
          ;; formatted may be nil if no matching zettels found
          (is (or (nil? (:formatted result))
                  (string? (:formatted result))))))

      (testing "inject-for-agent with empty store"
        (let [empty-store (knowledge/create-store)
              empty-coord (orch/create-knowledge-coordinator empty-store)
              task {:task/id (random-uuid) :task/type :test}
              result (proto/inject-for-agent empty-coord :planner task {})]
          ;; Should return result structure even with no matches
          (is (some? result))
          (is (= 0 (:count result))))))))

;; ============================================================================
;; Orchestrator creation tests
;; ============================================================================

(deftest create-orchestrator-test
  (testing "create-orchestrator returns an orchestrator"
    (let [orchestrator (create-test-orchestrator)]
      (is (some? orchestrator))
      (is (satisfies? proto/Orchestrator orchestrator)))))

(deftest create-router-test
  (testing "create-router returns a TaskRouter"
    (let [router (orch/create-router)]
      (is (satisfies? proto/TaskRouter router)))))

(deftest create-budget-manager-test
  (testing "create-budget-manager returns a BudgetManager"
    (let [budget-mgr (orch/create-budget-manager)]
      (is (satisfies? proto/BudgetManager budget-mgr)))))

(deftest create-knowledge-coordinator-test
  (testing "create-knowledge-coordinator returns a KnowledgeCoordinator"
    (let [store (knowledge/create-store)
          coord (orch/create-knowledge-coordinator store)]
      (is (satisfies? proto/KnowledgeCoordinator coord)))))

;; ============================================================================
;; Workflow helper tests
;; ============================================================================

(deftest can-handle-test
  (testing "can-handle? delegates to router"
    (let [router (orch/create-router)
          plan-task {:task/id (random-uuid) :task/type :plan}
          implement-task {:task/id (random-uuid) :task/type :implement}]
      (is (true? (orch/can-handle? router plan-task :planner)))
      (is (true? (orch/can-handle? router implement-task :implementer)))
      ;; Unknown tasks don't match any specific role
      (is (false? (orch/can-handle? router {:task/id (random-uuid) :task/type :unknown} :implementer))))))

(deftest route-task-test
  (testing "route-task returns agent role map"
    (let [router (orch/create-router)
          task {:task/id (random-uuid) :task/type :implement}
          result (orch/route-task router task {})]
      (is (= :implementer (:agent-role result)))
      (is (string? (:reason result))))))

(deftest check-budget-test
  (testing "check-budget returns budget status"
    (let [budget-mgr (orch/create-budget-manager)
          workflow-id (random-uuid)
          result (orch/check-budget budget-mgr workflow-id)]
      (is (map? result))
      (is (contains? result :within-budget?)))))

(deftest track-usage-test
  (testing "track-usage updates budget"
    (let [budget-mgr (orch/create-budget-manager)
          workflow-id (random-uuid)]
      (orch/set-budget budget-mgr workflow-id {:max-tokens 1000
                                                :max-cost-usd 10.0
                                                :timeout-ms 60000})
      (orch/track-usage budget-mgr workflow-id {:tokens 500})
      (let [{:keys [remaining]} (orch/check-budget budget-mgr workflow-id)]
        (is (= 500 (:tokens remaining)))))))

;; ============================================================================
;; Integration tests (with mocks)
;; ============================================================================

(deftest orchestrator-workflow-status-test
  (testing "get-workflow-status for non-existent workflow"
    (let [orchestrator (create-test-orchestrator)
          status (orch/get-workflow-status orchestrator (random-uuid))]
      (is (nil? status)))))

(deftest orchestrator-workflow-results-test
  (testing "get-workflow-results for non-existent workflow"
    (let [orchestrator (create-test-orchestrator)
          results (orch/get-workflow-results orchestrator (random-uuid))]
      (is (nil? results)))))

(deftest orchestrator-cancel-test
  (testing "cancel-workflow for non-existent workflow"
    (let [orchestrator (create-test-orchestrator)
          result (orch/cancel-workflow orchestrator (random-uuid))]
      ;; Returns nil (falsey) for non-existent workflow
      (is (not result)))))
