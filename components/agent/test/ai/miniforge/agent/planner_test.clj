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

(ns ai.miniforge.agent.planner-test
  "Tests for the Planner agent."
  (:require
   [clojure.test :as test :refer [deftest testing is]]
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.planner :as planner]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def valid-plan
  {:plan/id (random-uuid)
   :plan/name "test-plan"
   :plan/tasks [{:task/id (random-uuid)
                 :task/description "Implement feature"
                 :task/type :implement
                 :task/acceptance-criteria ["Feature works"]
                 :task/estimated-effort :medium}]
   :plan/estimated-complexity :medium
   :plan/risks ["None identified"]})

(def minimal-plan
  {:plan/id (random-uuid)
   :plan/name "minimal"
   :plan/tasks []})

;------------------------------------------------------------------------------ Layer 1
;; Agent creation tests

(deftest create-planner-test
  (testing "creates planner with default config"
    (let [agent (planner/create-planner)]
      (is (some? agent))
      (is (= :planner (:role agent)))
      (is (string? (:system-prompt agent)))
      (is (= {:tokens 100000 :cost-usd 5.0}
             (get-in agent [:config :budget])))))

  (testing "creates planner with custom config"
    (let [agent (planner/create-planner {:config {:temperature 0.5}})]
      (is (= 0.5 (get-in agent [:config :temperature])))))

  (testing "creates planner with logger"
    (let [[logger _] (log/collecting-logger)
          agent (planner/create-planner {:logger logger})]
      (is (some? (:logger agent))))))

;------------------------------------------------------------------------------ Layer 2
;; Invoke tests

(deftest planner-invoke-test
  (testing "throws when no LLM backend provided"
    (let [agent (planner/create-planner)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No LLM backend"
            (core/invoke agent {} "Build a user login system")))))

  (testing "throws with context but no LLM backend"
    (let [agent (planner/create-planner)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No LLM backend"
            (core/invoke agent {:codebase {:has-tests? true}}
                         "Add feature to existing codebase"))))))

;------------------------------------------------------------------------------ Layer 3
;; Validation tests

(deftest validate-plan-test
  (testing "valid plan passes validation"
    (let [result (planner/validate-plan valid-plan)]
      (is (:valid? result))
      (is (nil? (:errors result)))))

  (testing "minimal plan passes validation"
    (let [result (planner/validate-plan minimal-plan)]
      (is (:valid? result))))

  (testing "missing required fields fails validation"
    (let [result (planner/validate-plan {:plan/name "no-id"})]
      (is (not (:valid? result)))
      (is (some? (:errors result)))))

  (testing "invalid task dependencies fail validation"
    (let [bad-plan {:plan/id (random-uuid)
                    :plan/name "bad-deps"
                    :plan/tasks [{:task/id (random-uuid)
                                  :task/description "Task"
                                  :task/type :implement
                                  :task/dependencies [(random-uuid)]}]}
          result (planner/validate-plan bad-plan)]
      (is (not (:valid? result)))
      (is (contains? (:errors result) :dependencies))))

  (testing "self-dependency fails validation"
    (let [task-id (random-uuid)
          self-dep-plan {:plan/id (random-uuid)
                         :plan/name "self-dep"
                         :plan/tasks [{:task/id task-id
                                       :task/description "Task"
                                       :task/type :implement
                                       :task/dependencies [task-id]}]}
          result (planner/validate-plan self-dep-plan)]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Layer 3.5
;; Schema backward-compat & new-field tests

(deftest validate-plan-new-fields-test
  (testing "plan with task/component, exclusive-files, stratum validates"
    (let [plan {:plan/id (random-uuid)
                :plan/name "multi-component"
                :plan/tasks [{:task/id (random-uuid)
                              :task/description "Agent changes"
                              :task/type :implement
                              :task/component "agent"
                              :task/exclusive-files ["components/agent/src/foo.clj"]
                              :task/stratum 0}
                             {:task/id (random-uuid)
                              :task/description "Workflow changes"
                              :task/type :implement
                              :task/component "workflow"
                              :task/exclusive-files ["components/workflow/src/bar.clj"]
                              :task/stratum 0}]}
          result (planner/validate-plan plan)]
      (is (:valid? result))))

  (testing "plan without new fields still validates (backward compat)"
    (let [result (planner/validate-plan valid-plan)]
      (is (:valid? result))))

  (testing "invalid stratum type fails validation"
    (let [plan {:plan/id (random-uuid)
                :plan/name "bad-stratum"
                :plan/tasks [{:task/id (random-uuid)
                              :task/description "Task"
                              :task/type :implement
                              :task/stratum -1}]}
          result (planner/validate-plan plan)]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Layer 4
;; Utility tests

(deftest plan-summary-test
  (testing "returns plan summary"
    (let [summary (planner/plan-summary valid-plan)]
      (is (uuid? (:id summary)))
      (is (string? (:name summary)))
      (is (number? (:task-count summary)))
      (is (keyword? (:complexity summary)))
      (is (number? (:risk-count summary))))))

(deftest task-dependency-order-test
  (testing "returns tasks in dependency order"
    (let [task-a-id (random-uuid)
          task-b-id (random-uuid)
          task-c-id (random-uuid)
          plan {:plan/tasks [{:task/id task-b-id
                              :task/description "B depends on A"
                              :task/type :test
                              :task/dependencies [task-a-id]}
                             {:task/id task-a-id
                              :task/description "A is first"
                              :task/type :implement}
                             {:task/id task-c-id
                              :task/description "C depends on B"
                              :task/type :review
                              :task/dependencies [task-b-id]}]}
          ordered (planner/task-dependency-order plan)]
      (is (= 3 (count ordered)))
      ;; A should come before B, B before C
      (is (< (.indexOf (map :task/id ordered) task-a-id)
             (.indexOf (map :task/id ordered) task-b-id)))
      (is (< (.indexOf (map :task/id ordered) task-b-id)
             (.indexOf (map :task/id ordered) task-c-id)))))

  (testing "handles tasks with no dependencies"
    (let [plan {:plan/tasks [{:task/id (random-uuid)
                              :task/description "Independent A"
                              :task/type :implement}
                             {:task/id (random-uuid)
                              :task/description "Independent B"
                              :task/type :test}]}
          ordered (planner/task-dependency-order plan)]
      (is (= 2 (count ordered))))))

;------------------------------------------------------------------------------ Layer 5
;; Full cycle tests

(deftest planner-cycle-test
  (testing "full invoke-validate cycle throws without LLM"
    (let [agent (planner/create-planner)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No LLM backend"
            (core/cycle-agent agent {} "Create a REST API endpoint"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.planner-test)

  :leave-this-here)
