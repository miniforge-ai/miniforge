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

(ns ai.miniforge.workflow.agent-factory-test
  "Tests for workflow agent factory."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.agent-factory :as factory]
   [ai.miniforge.agent.interface :as agent]))

;------------------------------------------------------------------------------ Tests
;; Agent creation tests

(deftest test-create-agent-for-phase-planner
  (testing "Create planner agent from phase config"
    (let [mock-llm (agent/create-mock-llm {:content "test"})
          phase {:phase/id :plan
                 :phase/agent :planner}
          context {:llm-backend mock-llm}
          agent-instance (factory/create-agent-for-phase phase context)]
      (is (some? agent-instance)
          "Should create planner agent instance"))))

(deftest test-create-agent-for-phase-implementer
  (testing "Create implementer agent from phase config"
    (let [mock-llm (agent/create-mock-llm {:content "test"})
          phase {:phase/id :implement
                 :phase/agent :implementer}
          context {:llm-backend mock-llm}
          agent-instance (factory/create-agent-for-phase phase context)]
      (is (some? agent-instance)
          "Should create implementer agent instance"))))

(deftest test-create-agent-for-phase-tester
  (testing "Create tester agent from phase config"
    (let [mock-llm (agent/create-mock-llm {:content "test"})
          phase {:phase/id :verify
                 :phase/agent :tester}
          context {:llm-backend mock-llm}
          agent-instance (factory/create-agent-for-phase phase context)]
      (is (some? agent-instance)
          "Should create tester agent instance"))))

(deftest test-create-agent-for-phase-none
  (testing "Return nil for :none agent type"
    (let [phase {:phase/id :done
                 :phase/agent :none}
          context {}
          agent-instance (factory/create-agent-for-phase phase context)]
      (is (nil? agent-instance)
          "Should return nil for :none agent"))))

(deftest test-create-agent-for-phase-reviewer-throws
  (testing "Throw for :reviewer — requires :phase/handler or interceptor"
    (let [phase {:phase/id :review
                 :phase/agent :reviewer}
          context {}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires a :phase/handler"
                            (factory/create-agent-for-phase phase context))))))

(deftest test-create-agent-for-phase-releaser-throws
  (testing "Throw for :releaser — requires :phase/handler or interceptor"
    (let [phase {:phase/id :release
                 :phase/agent :releaser}
          context {}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires a :phase/handler"
                            (factory/create-agent-for-phase phase context))))))

(deftest test-create-agent-for-phase-observer-throws
  (testing "Throw for :observer — requires :phase/handler or interceptor"
    (let [phase {:phase/id :observe
                 :phase/agent :observer}
          context {}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires a :phase/handler"
                            (factory/create-agent-for-phase phase context))))))

(deftest test-create-agent-for-phase-unknown-throws
  (testing "Throw for unknown agent type"
    (let [phase {:phase/id :custom
                 :phase/agent :nonexistent}
          context {}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown agent type"
                            (factory/create-agent-for-phase phase context))))))

;------------------------------------------------------------------------------ Gate tests

(deftest test-create-gates-for-phase-syntax
  (testing "Create syntax gate from phase config"
    (let [phase {:phase/id :implement
                 :phase/gates [:syntax-valid]}
          context {}
          gates (factory/create-gates-for-phase phase context)]
      (is (= 1 (count gates))
          "Should create one gate")
      (is (some? (first gates))
          "Gate should not be nil"))))

(deftest test-create-gates-for-phase-multiple
  (testing "Create multiple gates from phase config"
    (let [phase {:phase/id :implement
                 :phase/gates [:syntax-valid :lint-clean :no-secrets]}
          context {}
          gates (factory/create-gates-for-phase phase context)]
      (is (= 3 (count gates))
          "Should create three gates"))))

(deftest test-create-gates-for-phase-empty
  (testing "Return empty vector when no gates specified"
    (let [phase {:phase/id :plan
                 :phase/gates []}
          context {}
          gates (factory/create-gates-for-phase phase context)]
      (is (empty? gates)
          "Should return empty vector"))))

;------------------------------------------------------------------------------ Task creation tests

(deftest test-create-task-for-phase
  (testing "Create task from phase config"
    (let [phase {:phase/id :implement
                 :phase/name "Implementation"
                 :phase/description "Implement the feature"}
          exec-state {:execution/input {:task "Test task"}
                      :execution/artifacts []}
          context {}
          task (factory/create-task-for-phase phase exec-state context)]
      (is (some? (:task/id task))
          "Task should have ID")
      (is (= :implement (:task/type task))
          "Task type should match phase ID")
      (is (= "Implementation" (:task/title task))
          "Task title should match phase name")
      (is (= "Implement the feature" (:task/description task))
          "Task description should match phase description"))))

;------------------------------------------------------------------------------ Generate function tests

(deftest test-create-generate-fn-with-agent
  (testing "Create generate function with real agent"
    (let [mock-llm (agent/create-mock-llm {:content "test output"
                                            :usage {:input-tokens 10 :output-tokens 5}})
          phase-agent (agent/create-planner {:llm-backend mock-llm})
          context {:llm-backend mock-llm}
          generate-fn (factory/create-generate-fn phase-agent context)
          task {:task/id (random-uuid)
                :task/type :plan
                :task/title "Test"}]
      (is (fn? generate-fn)
          "Should return a function")
      (let [result (generate-fn task context)]
        (is (map? result)
            "Should return a map")
        (is (contains? result :artifact)
            "Result should contain :artifact")
        (is (contains? result :tokens)
            "Result should contain :tokens")))))

(deftest test-create-generate-fn-without-agent
  (testing "Create generate function without agent (stub)"
    (let [generate-fn (factory/create-generate-fn nil {})
          task {:task/id (random-uuid)
                :task/type :plan}
          result (generate-fn task {})]
      (is (map? result)
          "Should return a map")
      (is (contains? result :artifact)
          "Result should contain :artifact")
      (is (= 0 (:tokens result))
          "Stub should return 0 tokens")
      (is (true? (get-in result [:artifact :artifact/content :stub]))
          "Artifact should be marked as stub"))))

;------------------------------------------------------------------------------ Repair function tests

(deftest test-create-repair-fn-with-agent
  (testing "Create repair function with real agent"
    (let [mock-llm (agent/create-mock-llm {:content "fixed output"})
          phase-agent (agent/create-planner {:llm-backend mock-llm})
          context {:llm-backend mock-llm}
          repair-fn (factory/create-repair-fn phase-agent context)]
      (is (fn? repair-fn)
          "Should return a function")
      (let [artifact {:artifact/id (random-uuid)
                      :artifact/content "broken"}
            errors [{:code :syntax-error :message "Error"}]
            result (repair-fn artifact errors context)]
        (is (map? result)
            "Should return a map")
        (is (contains? result :success?)
            "Result should contain :success?")
        (is (contains? result :artifact)
            "Result should contain :artifact")))))

(deftest test-create-repair-fn-without-agent
  (testing "Create repair function without agent (stub)"
    (let [repair-fn (factory/create-repair-fn nil {})
          artifact {:artifact/id (random-uuid)}
          errors [{:code :error}]
          result (repair-fn artifact errors {})]
      (is (false? (:success? result))
          "Stub should return failure")
      (is (= 0 (:tokens-used result))
          "Stub should return 0 tokens"))))

;------------------------------------------------------------------------------ Artifact building tests

(deftest test-build-artifact-for-phase
  (testing "Build standard artifact from phase result"
    (let [raw-artifact {:plan/id (random-uuid)
                        :plan/title "Test Plan"
                        :plan/tasks []}
          phase {:phase/id :plan
                 :phase/name "Planning"}
          metrics {:tokens 100 :cost-usd 0.01 :duration-ms 1000}
          artifact (factory/build-artifact-for-phase raw-artifact phase metrics)]
      (is (some? (:artifact/id artifact))
          "Artifact should have ID")
      (is (= :plan (:artifact/type artifact))
          "Artifact type should match phase")
      (is (= "1.0.0" (:artifact/version artifact))
          "Artifact should have version")
      (is (= raw-artifact (:artifact/content artifact))
          "Artifact content should be raw artifact")
      (is (= :plan (get-in artifact [:artifact/metadata :phase]))
          "Metadata should include phase ID"))))
