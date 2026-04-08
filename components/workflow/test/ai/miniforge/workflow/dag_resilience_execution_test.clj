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

(ns ai.miniforge.workflow.dag-resilience-execution-test
  "Tests for DAG execution pausing on rate limits and plan->dag-tasks dependency handling."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn ok-result [task-id]
  (dag/ok {:task-id task-id :status :implemented}))

(defn rate-limit-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

(defn generic-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

;------------------------------------------------------------------------------ Layer 4
;; DAG orchestrator integration — pause on rate limit

(deftest test-dag-execution-pauses-on-rate-limit
  (testing "DAG execution returns paused result when all tasks hit rate limits"
    (let [[logger _] (log/collecting-logger)
          ;; Use real UUIDs as task IDs since plan->dag-tasks expects them
          task-a (random-uuid)
          task-b (random-uuid)
          plan {:plan/id (random-uuid)
                :plan/name "test-plan"
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies []}]}
          ;; Mock execute-single-task to return rate limit errors
          _original-fn @(resolve 'ai.miniforge.workflow.dag-orchestrator/execute-single-task)]
      (with-redefs [ai.miniforge.workflow.dag-orchestrator/execute-single-task
                    (fn [_task-def _context]
                      (dag/err :task-execution-failed
                               "You've hit your limit · resets 2pm"
                               {}))]
        (let [result (ai.miniforge.workflow.dag-orchestrator/execute-plan-as-dag
                      plan {:logger logger})]
          (is (not (:success? result)))
          (is (true? (:paused? result)))
          (is (string? (:pause-reason result))))))))

(deftest test-dag-execution-partial-rate-limit
  (testing "DAG pauses when some tasks succeed and others hit rate limits"
    (let [[logger _] (log/collecting-logger)
          task-a (random-uuid)
          task-b (random-uuid)
          call-count (atom 0)
          plan {:plan/id (random-uuid)
                :plan/name "test-plan"
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies []}]}]
      (with-redefs [ai.miniforge.workflow.dag-orchestrator/execute-single-task
                    (fn [task-def _context]
                      (let [n (swap! call-count inc)]
                        (if (= n 1)
                          ;; First task succeeds
                          (dag/ok {:task-id (:task/id task-def)
                                   :status :implemented
                                   :artifacts []
                                   :metrics {:tokens 0 :cost-usd 0.0}})
                          ;; Second task hits rate limit
                          (dag/err :task-execution-failed
                                   "429 Too Many Requests"
                                   {}))))]
        (let [result (ai.miniforge.workflow.dag-orchestrator/execute-plan-as-dag
                      plan {:logger logger})]
          (is (not (:success? result)))
          (is (true? (:paused? result)))
          ;; One task completed, one rate-limited
          (is (= 1 (:tasks-completed result))))))))

(deftest test-dag-execution-pre-completed-ids
  (testing "DAG skips tasks in pre-completed-ids set"
    (let [[logger _] (log/collecting-logger)
          task-a (random-uuid)
          task-b (random-uuid)
          executed-tasks (atom #{})
          plan {:plan/id (random-uuid)
                :plan/name "test-plan"
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies []}]}]
      (with-redefs [ai.miniforge.workflow.dag-orchestrator/execute-single-task
                    (fn [task-def _context]
                      (swap! executed-tasks conj (:task/id task-def))
                      (dag/ok {:task-id (:task/id task-def)
                               :status :implemented
                               :artifacts []
                               :metrics {:tokens 0 :cost-usd 0.0}}))]
        (let [result (ai.miniforge.workflow.dag-orchestrator/execute-plan-as-dag
                      plan {:logger logger
                            :pre-completed-ids #{task-a}})]
          (is (:success? result))
          ;; Only task-b should have been executed
          (is (= #{task-b} @executed-tasks))
          ;; Both count as completed (1 pre-completed + 1 executed)
          (is (= 2 (:tasks-completed result))))))))

;------------------------------------------------------------------------------ Layer 5
;; Regression: phantom dependency detection and unreached task reporting
;;
;; Bug: Planner generates dependency UUIDs referencing non-existent tasks.
;; Tasks with phantom deps never become ready (dep not in completed-ids)
;; and never fail (nothing actually failed). The loop exits on
;; (empty? ready-tasks) and silently drops them — reporting success
;; with 0 failures despite tasks never running.

(deftest test-plan->dag-tasks-drops-phantom-deps
  (testing "dependencies referencing non-existent task IDs are dropped"
    (let [task-a (random-uuid)
          task-b (random-uuid)
          phantom (random-uuid)
          plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies [task-a phantom]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})]
      ;; Task B should only have task-a as a dep; phantom is dropped
      (is (= #{task-a} (:task/deps (second dag-tasks))))
      ;; Task A has no deps
      (is (empty? (:task/deps (first dag-tasks)))))))

(deftest test-plan->dag-tasks-drops-string-phantom-deps
  (testing "string UUID dependencies to non-existent tasks are also dropped"
    (let [task-a (random-uuid)
          task-b (random-uuid)
          phantom-str (str (random-uuid))
          plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies [(str task-a) phantom-str]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})]
      ;; task-a string should resolve; phantom string should be dropped
      (is (= #{task-a} (:task/deps (second dag-tasks)))))))

(deftest test-plan->dag-tasks-all-deps-valid
  (testing "valid dependencies are preserved unchanged"
    (let [task-a (random-uuid)
          task-b (random-uuid)
          task-c (random-uuid)
          plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id task-a
                              :task/description "A" :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "B" :task/type :implement
                              :task/dependencies []}
                             {:task/id task-c
                              :task/description "C" :task/type :implement
                              :task/dependencies [task-a task-b]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          task-c-dag (first (filter #(= task-c (:task/id %)) dag-tasks))]
      (is (= #{task-a task-b} (:task/deps task-c-dag))))))

(deftest test-plan->dag-tasks-keyword-task-ids
  (testing "keyword task IDs are preserved and dependencies resolve against them"
    (let [plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id :task-a
                              :task/description "A" :task/type :implement
                              :task/dependencies []}
                             {:task/id :task-b
                              :task/description "B" :task/type :implement
                              :task/dependencies [:task-a]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          id-a (:task/id (first dag-tasks))
          id-b (:task/id (second dag-tasks))]
      (is (= :task-a id-a))
      (is (= :task-b id-b))
      ;; Dependency resolved correctly
      (is (= #{id-a} (:task/deps (second dag-tasks)))))))

(deftest test-plan->dag-tasks-keyword-deps-resolve
  (testing "keyword dependencies resolve to the correct normalized task IDs"
    (let [plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id :alpha
                              :task/description "Alpha" :task/type :implement
                              :task/dependencies []}
                             {:task/id :beta
                              :task/description "Beta" :task/type :implement
                              :task/dependencies []}
                             {:task/id :gamma
                              :task/description "Gamma" :task/type :implement
                              :task/dependencies [:alpha :beta]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          ids (into {} (map (juxt :task/description :task/id) dag-tasks))
          gamma-task (first (filter #(= "Gamma" (:task/description %)) dag-tasks))]
      (is (= #{(ids "Alpha") (ids "Beta")} (:task/deps gamma-task))))))

(deftest test-plan->dag-tasks-mixed-id-types
  (testing "plan with mixed UUID and keyword IDs preserves both consistently"
    (let [uuid-id (random-uuid)
          plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id uuid-id
                              :task/description "UUID task" :task/type :implement
                              :task/dependencies []}
                             {:task/id :keyword-task
                              :task/description "Keyword task" :task/type :implement
                              :task/dependencies [uuid-id]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})]
      ;; UUID task ID preserved as-is
      (is (= uuid-id (:task/id (first dag-tasks))))
      ;; Keyword task ID preserved as-is
      (is (= :keyword-task (:task/id (second dag-tasks))))
      ;; Dependency on UUID task resolves
      (is (= #{uuid-id} (:task/deps (second dag-tasks)))))))

(deftest test-plan->dag-tasks-string-non-uuid-ids
  (testing "non-UUID string task IDs are preserved and dependencies resolve against them"
    (let [plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id "build-fixtures"
                              :task/description "Build" :task/type :implement
                              :task/dependencies []}
                             {:task/id "run-tests"
                              :task/description "Test" :task/type :test
                              :task/dependencies ["build-fixtures"]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          id-build (:task/id (first dag-tasks))
          id-test (:task/id (second dag-tasks))]
      (is (= "build-fixtures" id-build))
      (is (= "run-tests" id-test))
      (is (= #{id-build} (:task/deps (second dag-tasks)))))))

(deftest test-phantom-deps-caused-stuck-tasks-before-fix
  (testing "regression: phantom deps no longer cause silently stuck tasks"
    (let [[logger _] (log/collecting-logger)
          task-a (random-uuid)
          task-b (random-uuid)
          task-c (random-uuid)
          phantom (random-uuid)
          ;; task-c depends on task-a (valid) and phantom (non-existent)
          ;; Before fix: task-c would never run and never be reported as failed
          plan {:plan/id (random-uuid)
                :plan/name "phantom-dep-plan"
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-c
                              :task/description "Task C"
                              :task/type :implement
                              :task/dependencies [task-a phantom]}]}
          executed-tasks (atom #{})]
      (with-redefs [dag-orch/execute-single-task
                    (fn [task-def _context]
                      (swap! executed-tasks conj (:task/id task-def))
                      (dag/ok {:task-id (:task/id task-def)
                               :status :implemented
                               :artifacts []
                               :metrics {:tokens 0 :cost-usd 0.0}}))]
        (let [result (dag-orch/execute-plan-as-dag plan {:logger logger})]
          ;; All 3 tasks should complete — phantom dep dropped at plan conversion
          (is (:success? result))
          (is (= 3 (:tasks-completed result)))
          (is (= 0 (:tasks-failed result)))
          (is (= #{task-a task-b task-c} @executed-tasks)))))))

(deftest test-unreached-tasks-reported-in-result
  (testing "tasks stuck due to failed deps are reported as unreached"
    (let [[logger _] (log/collecting-logger)
          task-a (random-uuid)
          task-b (random-uuid)
          task-c (random-uuid)
          ;; task-c depends on task-b, which will fail
          plan {:plan/id (random-uuid)
                :plan/name "unreached-plan"
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-c
                              :task/description "Task C — depends on B"
                              :task/type :implement
                              :task/dependencies [task-b]}]}]
      (with-redefs [dag-orch/execute-single-task
                    (fn [task-def _context]
                      (if (= (:task/id task-def) task-b)
                        (dag/err :task-execution-failed
                                 "Compilation error" {})
                        (dag/ok {:task-id (:task/id task-def)
                                 :status :implemented
                                 :artifacts []
                                 :metrics {:tokens 0 :cost-usd 0.0}})))]
        (let [result (dag-orch/execute-plan-as-dag plan {:logger logger})]
          ;; task-a completed, task-b failed, task-c transitively failed
          (is (not (:success? result)))
          (is (= 1 (:tasks-completed result)))
          ;; task-b failed directly + task-c transitively = 2 failed
          (is (= 2 (:tasks-failed result)))
          ;; No tasks unreached — all accounted for via propagation
          (is (= 0 (or (:tasks-unreached result) 0))))))))

(deftest test-all-tasks-accounted-for
  (testing "completed + failed + unreached = total (no silent data loss)"
    (let [[logger _] (log/collecting-logger)
          task-ids (repeatedly 6 random-uuid)
          [a b c d e f] task-ids
          ;; a, b: independent. c depends on a. d depends on b.
          ;; e depends on c and d. f: independent.
          ;; b will fail -> d transitively fails -> e transitively fails
          plan {:plan/id (random-uuid)
                :plan/name "accounting-plan"
                :plan/tasks [{:task/id a :task/description "A"
                              :task/type :implement :task/dependencies []}
                             {:task/id b :task/description "B"
                              :task/type :implement :task/dependencies []}
                             {:task/id c :task/description "C"
                              :task/type :implement :task/dependencies [a]}
                             {:task/id d :task/description "D"
                              :task/type :implement :task/dependencies [b]}
                             {:task/id e :task/description "E"
                              :task/type :implement :task/dependencies [c d]}
                             {:task/id f :task/description "F"
                              :task/type :implement :task/dependencies []}]}]
      (with-redefs [dag-orch/execute-single-task
                    (fn [task-def _context]
                      (if (= (:task/id task-def) b)
                        (dag/err :task-execution-failed "fail" {})
                        (dag/ok {:task-id (:task/id task-def)
                                 :status :implemented :artifacts []
                                 :metrics {:tokens 0 :cost-usd 0.0}})))]
        (let [result (dag-orch/execute-plan-as-dag plan {:logger logger})
              total 6
              accounted (+ (:tasks-completed result)
                           (:tasks-failed result)
                           (or (:tasks-unreached result) 0))]
          ;; a, c, f complete. b fails. d, e transitively fail.
          (is (= 3 (:tasks-completed result)))
          (is (= 3 (:tasks-failed result)))
          (is (= total accounted)
              "Every task must be accounted for — no silent drops"))))))

(deftest test-sub-workflow-strips-release-phase
  (testing "sub-workflow pipeline excludes :explore, :plan, and :release phases"
    (let [task-def {:task/id (random-uuid)
                    :task/description "Test task"}
          context {:execution/workflow
                   {:workflow/pipeline
                    [{:phase :explore}
                     {:phase :plan}
                     {:phase :implement}
                     {:phase :verify}
                     {:phase :review}
                     {:phase :release}
                     {:phase :done}]}}
          sub-wf (dag-orch/task-sub-workflow task-def context)
          phase-names (mapv :phase (:workflow/pipeline sub-wf))]
      (is (= [:implement :verify :review :release :done] phase-names)
          "Sub-workflow should strip :explore, :plan, and :observe but keep :release")))

  (testing "sub-workflow with no parent pipeline falls back to minimal"
    (let [task-def {:task/id (random-uuid)
                    :task/description "Test task"}
          context {:execution/workflow {:workflow/pipeline []}}
          sub-wf (dag-orch/task-sub-workflow task-def context)
          phase-names (mapv :phase (:workflow/pipeline sub-wf))]
      (is (= [:implement :release :done] phase-names)))))
