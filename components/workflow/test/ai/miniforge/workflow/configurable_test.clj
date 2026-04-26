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
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.configurable :as configurable]
   [clojure.test :refer [deftest is testing]]))

(def simple-workflow
  {:workflow/id :test
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
   :workflow/exit-phases [:end]})

(deftest find-phase-test
  (testing "Find phase by ID in workflow"
    (let [phase (configurable/find-phase simple-workflow :start)]
      (is (some? phase))
      (is (= :start (:phase/id phase)))
      (is (= "Start" (:phase/name phase)))))

  (testing "Find non-existent phase returns nil"
    (is (nil? (configurable/find-phase simple-workflow :missing)))))

(deftest execute-configurable-phase-test
  (testing "Execute normal phase returns success"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"})
          phase-config {:phase/id :plan
                        :phase/name "Plan"
                        :phase/agent :planner
                        :phase/gates []}
          result (configurable/execute-configurable-phase phase-config {} {:llm-backend mock-llm})]
      (is (true? (:success? result)))
      (is (vector? (:artifacts result)))
      (is (seq (:artifacts result)))
      (is (empty? (:errors result)))
      (is (map? (:metrics result)))))

  (testing "Execute done phase returns success with no artifacts"
    (let [phase-config {:phase/id :done
                        :phase/name "Done"
                        :phase/agent :none}
          result (configurable/execute-configurable-phase phase-config {} {})]
      (is (true? (:success? result)))
      (is (empty? (:artifacts result)))
      (is (zero? (:tokens (:metrics result))))))

  (testing "Execute handler-backed phase uses caller-provided handler"
    (let [artifact-id (random-uuid)
          phase-config {:phase/id :acquire
                        :phase/name "Acquire"
                        :phase/agent :none
                        :phase/handler :etl/acquire}
          result (configurable/execute-configurable-phase
                  phase-config
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
          exec-state (configurable/run-configurable-workflow
                      simple-workflow
                      {:task "Test"}
                      {:llm-backend mock-llm})]
      (is (phase/succeeded? exec-state))
      (is (= :completed (:execution/status exec-state)))
      (is (= #{:start :end} (set (keys (:execution/phase-results exec-state)))))
      (is (nil? (:execution/current-phase exec-state)))
      (is (>= (get-in exec-state [:execution/metrics :tokens] 0) 0))))

  (testing "Run workflow honors declared entry phase instead of declaration order"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"})
          workflow {:workflow/id :entry-test
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :end
                      :phase/name "End"
                      :phase/agent :none
                      :phase/next []}
                     {:phase/id :start
                      :phase/name "Start"
                      :phase/agent :planner
                      :phase/next [{:target :end}]}]
                    :workflow/entry-phase :start}
          exec-state (configurable/run-configurable-workflow
                      workflow
                      {:task "Test"}
                      {:llm-backend mock-llm})]
      (is (phase/succeeded? exec-state))
      (is (contains? (:execution/phase-results exec-state) :start))
      (is (contains? (:execution/phase-results exec-state) :end))))

  (testing "Run workflow with callbacks"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"})
          phase-starts (atom [])
          phase-completes (atom [])
          context {:llm-backend mock-llm
                   :on-phase-start (fn [_state phase-config]
                                     (swap! phase-starts conj (:phase/id phase-config)))
                   :on-phase-complete (fn [_state phase-config _result]
                                        (swap! phase-completes conj (:phase/id phase-config)))}
          exec-state (configurable/run-configurable-workflow
                      simple-workflow
                      {:task "Test"}
                      context)]
      (is (phase/succeeded? exec-state))
      (is (= [:start :end] @phase-starts))
      (is (= [:start :end] @phase-completes))))

  (testing "Run workflow with max-phases limit"
    (let [mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"})
          workflow {:workflow/id :loop-test
                    :workflow/version "1.0.0"
                    :workflow/phases
                    [{:phase/id :loop
                      :phase/name "Loop"
                      :phase/agent :planner
                      :phase/task-type :plan
                      :phase/next [{:target :loop}]}]
                    :workflow/entry-phase :loop}
          exec-state (configurable/run-configurable-workflow
                      workflow
                      {:task "Test"}
                      {:llm-backend mock-llm
                       :max-phases 5})]
      (is (phase/failed? exec-state))
      (is (some #(= :max-phases-exceeded (:type %))
                (:execution/errors exec-state)))))

  (testing "Run workflow fails when active phase is missing"
    (let [mock-llm (agent/create-mock-llm {:content "test"})
          workflow {:workflow/id :missing-phase
                    :workflow/version "1.0.0"
                    :workflow/phases []
                    :workflow/entry-phase :ghost}
          exec-state (configurable/run-configurable-workflow
                      workflow
                      {:task "Test"}
                      {:llm-backend mock-llm})]
      (is (phase/failed? exec-state))
      (is (some #(= :phase-not-found (:type %))
                (:execution/errors exec-state))))))

(deftest workflow-execution-flow-test
  (testing "Complete workflow execution follows compiled-machine transitions"
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
                    :workflow/entry-phase :plan}
          exec-state (configurable/run-configurable-workflow
                      workflow
                      {:task "Full workflow test"}
                      {:llm-backend mock-llm})]
      (is (phase/succeeded? exec-state))
      (is (= #{:plan :implement :verify :done}
             (set (keys (:execution/phase-results exec-state)))))
      (is (= :completed (:execution/status exec-state)))
      (is (nil? (:execution/current-phase exec-state))))))
