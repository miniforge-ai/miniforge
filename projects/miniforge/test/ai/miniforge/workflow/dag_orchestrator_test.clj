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

(ns ai.miniforge.workflow.dag-orchestrator-test
  "Tests for DAG orchestration of multi-task plans."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.dag-task-runner :as task-runner]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn make-task
  "Create a task with given id, description, and dependencies."
  ([id] (make-task id (str "Task " id) []))
  ([id deps] (make-task id (str "Task " id) deps))
  ([id description deps]
   {:task/id id
    :task/description description
    :task/type :implement
    :task/dependencies deps}))

(defn make-plan
  "Create a plan with given tasks."
  [tasks]
  {:plan/id (random-uuid)
   :plan/name "test-plan"
   :plan/tasks tasks})

;------------------------------------------------------------------------------ Layer 1
;; parallelizable-plan? tests

(deftest test-parallelizable-plan-empty
  (testing "empty plan is not parallelizable"
    (is (not (dag-orch/parallelizable-plan? {:plan/tasks []})))))

(deftest test-parallelizable-plan-single-task
  (testing "single task plan is not parallelizable"
    (let [plan (make-plan [(make-task :a)])]
      (is (not (dag-orch/parallelizable-plan? plan))))))

(deftest test-parallelizable-plan-two-independent
  (testing "two independent tasks are parallelizable"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b)])]
      (is (dag-orch/parallelizable-plan? plan)))))

(deftest test-parallelizable-plan-sequential-chain
  (testing "sequential chain is not parallelizable"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b [:a])])]
      (is (not (dag-orch/parallelizable-plan? plan))))))

(deftest test-parallelizable-plan-diamond
  (testing "diamond pattern (A→B,C→D) is parallelizable"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b [:a])
                           (make-task :c [:a])
                           (make-task :d [:b :c])])]
      (is (dag-orch/parallelizable-plan? plan)))))

(deftest test-parallelizable-plan-fan-out
  (testing "fan-out pattern (A→B,C,D) is parallelizable"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b [:a])
                           (make-task :c [:a])
                           (make-task :d [:a])])]
      (is (dag-orch/parallelizable-plan? plan)))))

;------------------------------------------------------------------------------ Layer 2
;; estimate-parallel-speedup tests

(deftest test-estimate-speedup-empty
  (testing "empty plan has no speedup"
    (let [result (dag-orch/estimate-parallel-speedup {:plan/tasks []})]
      (is (= 0 (:task-count result)))
      (is (= 0 (:max-parallel result)))
      (is (false? (:parallelizable? result))))))

(deftest test-estimate-speedup-single
  (testing "single task has no speedup"
    (let [result (dag-orch/estimate-parallel-speedup
                  (make-plan [(make-task :a)]))]
      (is (= 1 (:task-count result)))
      (is (= 1 (:max-parallel result)))
      (is (= 1 (:levels result)))
      (is (false? (:parallelizable? result)))
      (is (= 1.0 (:estimated-speedup result))))))

(deftest test-estimate-speedup-two-parallel
  (testing "two parallel tasks have 2x speedup"
    (let [result (dag-orch/estimate-parallel-speedup
                  (make-plan [(make-task :a)
                              (make-task :b)]))]
      (is (= 2 (:task-count result)))
      (is (= 2 (:max-parallel result)))
      (is (= 1 (:levels result)))
      (is (true? (:parallelizable? result)))
      (is (= 2.0 (:estimated-speedup result))))))

(deftest test-estimate-speedup-diamond
  (testing "diamond pattern has correct speedup"
    (let [result (dag-orch/estimate-parallel-speedup
                  (make-plan [(make-task :a)
                              (make-task :b [:a])
                              (make-task :c [:a])
                              (make-task :d [:b :c])]))]
      (is (= 4 (:task-count result)))
      (is (= 2 (:max-parallel result)))
      (is (= 3 (:levels result)))
      (is (true? (:parallelizable? result)))
      ;; 4 tasks / 3 levels ≈ 1.33
      (is (< 1.3 (:estimated-speedup result) 1.4)))))

(deftest test-estimate-speedup-wide-fan
  (testing "wide fan-out has high parallelism"
    (let [result (dag-orch/estimate-parallel-speedup
                  (make-plan [(make-task :root)
                              (make-task :a [:root])
                              (make-task :b [:root])
                              (make-task :c [:root])
                              (make-task :d [:root])]))]
      (is (= 5 (:task-count result)))
      (is (= 4 (:max-parallel result)))
      (is (= 2 (:levels result)))
      (is (true? (:parallelizable? result)))
      (is (= 2.5 (:estimated-speedup result))))))

;------------------------------------------------------------------------------ Layer 3
;; plan->dag-tasks tests

(deftest test-plan-to-dag-tasks-basic
  (testing "converts plan tasks to DAG format"
    (let [plan (make-plan [(make-task :a "Task A" [])
                           (make-task :b "Task B" [:a])])
          context {:workflow-id (random-uuid)}
          result (dag-orch/plan->dag-tasks plan context)]
      (is (= 2 (count result)))
      (is (= :a (:task/id (first result))))
      (is (= "Task A" (:task/description (first result))))
      (is (= #{} (:task/deps (first result))))
      (is (= :b (:task/id (second result))))
      (is (= #{:a} (:task/deps (second result)))))))

(deftest test-plan-to-dag-tasks-context
  (testing "includes context in task definitions"
    (let [plan-id (random-uuid)
          workflow-id (random-uuid)
          plan {:plan/id plan-id
                :plan/tasks [(make-task :a)]}
          context {:workflow-id workflow-id
                   :llm-backend :mock-llm
                   :artifact-store :mock-store}
          result (dag-orch/plan->dag-tasks plan context)
          task-context (:task/context (first result))]
      (is (= plan-id (:parent-plan-id task-context)))
      (is (= workflow-id (:parent-workflow-id task-context)))
      (is (= :mock-llm (:llm-backend task-context)))
      (is (= :mock-store (:artifact-store task-context))))))

(deftest test-plan-to-dag-tasks-defaults
  (testing "uses defaults for missing fields"
    (let [plan {:plan/tasks [{:task/id :minimal}]}
          result (dag-orch/plan->dag-tasks plan {})]
      (is (= :minimal (:task/id (first result))))
      (is (= :implement (:task/type (first result))))
      (is (= #{} (:task/deps (first result))))
      (is (= [] (:task/acceptance-criteria (first result)))))))

;------------------------------------------------------------------------------ Layer 4
;; execute-single-task tests

(deftest test-execute-single-task-placeholder
  (testing "returns placeholder result without LLM backend"
    (let [task-def {:task/id :test-task
                    :task/description "Test task"}
          result (task-runner/execute-single-task task-def {})]
      (is (dag/ok? result))
      (let [data (dag/unwrap result)]
        (is (= :test-task (:task-id data)))
        (is (= "Test task" (:description data)))
        (is (= :implemented (:status data)))
        (is (= [] (:artifacts data)))
        (is (= {:tokens 0 :cost-usd 0.0} (:metrics data)))))))

(deftest test-execute-single-task-default-description
  (testing "uses default description when missing"
    (let [task-def {:task/id :no-desc}
          result (task-runner/execute-single-task task-def {})]
      (is (dag/ok? result))
      (is (= "Implement task" (:description (dag/unwrap result)))))))

;------------------------------------------------------------------------------ Layer 5
;; create-task-executor-fn tests

(deftest test-create-executor-fn-callbacks
  (testing "executor fn invokes callbacks"
    (let [started (atom [])
          completed (atom [])
          context {}
          opts {:on-task-start #(swap! started conj %)
                :on-task-complete #(swap! completed conj [%1 %2])}
          executor (task-runner/create-task-executor-fn context opts)
          dag-context {:run-state {:run/tasks {:task-1 {:task/id :task-1
                                                         :task/description "Test"}}}}
          result (executor :task-1 dag-context)]
      (is (= [:task-1] @started))
      (is (= 1 (count @completed)))
      (is (= :task-1 (first (first @completed))))
      (is (dag/ok? result)))))

(deftest test-create-executor-fn-no-callbacks
  (testing "executor fn works without callbacks"
    (let [executor (task-runner/create-task-executor-fn {} {})
          dag-context {:run-state {:run/tasks {:task-1 {:task/id :task-1}}}}
          result (executor :task-1 dag-context)]
      (is (dag/ok? result)))))

;------------------------------------------------------------------------------ Layer 6
;; execute-plan-as-dag tests

(deftest test-execute-plan-as-dag-success
  (testing "executes parallel plan successfully"
    (let [[logger _] (log/collecting-logger)
          plan (make-plan [(make-task :a)
                           (make-task :b)])
          context {:logger logger}
          result (dag-orch/execute-plan-as-dag plan context)]
      (is (:success? result))
      (is (= 2 (:tasks-completed result)))
      (is (= 0 (:tasks-failed result))))))

(deftest test-execute-plan-as-dag-with-deps
  (testing "executes plan with dependencies"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b)
                           (make-task :c [:a :b])])
          result (dag-orch/execute-plan-as-dag plan {})]
      (is (:success? result))
      (is (= 3 (:tasks-completed result))))))

(deftest test-execute-plan-as-dag-callbacks
  (testing "invokes task callbacks"
    (let [started (atom [])
          completed (atom [])
          plan (make-plan [(make-task :a)
                           (make-task :b)])
          context {:on-task-start #(swap! started conj %)
                   :on-task-complete (fn [id _] (swap! completed conj id))}
          result (dag-orch/execute-plan-as-dag plan context)]
      (is (:success? result))
      (is (= 2 (count @started)))
      (is (= 2 (count @completed)))
      (is (= (set @started) (set @completed))))))

;------------------------------------------------------------------------------ Layer 7
;; maybe-parallelize-plan tests

(deftest test-maybe-parallelize-sequential
  (testing "returns nil for sequential plan"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b [:a])])]
      (is (nil? (dag-orch/maybe-parallelize-plan plan {}))))))

(deftest test-maybe-parallelize-parallel
  (testing "executes parallelizable plan"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b)])
          result (dag-orch/maybe-parallelize-plan plan {})]
      (is (some? result))
      (is (:success? result))
      (is (= 2 (:tasks-completed result))))))

(deftest test-maybe-parallelize-logs
  (testing "logs parallelization decision"
    (let [[logger entries] (log/collecting-logger)
          plan (make-plan [(make-task :a)
                           (make-task :b)
                           (make-task :c)])
          _ (dag-orch/maybe-parallelize-plan plan {:logger logger})]
      (is (some #(= :plan/parallelizing (:log/event %)) @entries)))))

;------------------------------------------------------------------------------ Layer 8
;; Resume from pre-completed-ids tests

(deftest test-execute-plan-with-pre-completed-ids
  (testing "skips pre-completed tasks and only executes remaining"
    (let [started (atom [])
          plan (make-plan [(make-task :a)
                           (make-task :b)
                           (make-task :c [:a :b])])
          ;; Mark :a as already completed from a prior run
          context {:pre-completed-ids #{:a}
                   :on-task-start #(swap! started conj %)}
          result (dag-orch/execute-plan-as-dag plan context)]
      (is (:success? result))
      ;; :a was pre-completed, so only :b and :c should have been executed
      (is (not (some #{:a} @started)) ":a should not have been re-executed")
      ;; :b and :c should run (total completed = 3 including pre-completed)
      (is (= 3 (:tasks-completed result))))))

(deftest test-execute-plan-with-all-pre-completed
  (testing "returns immediately when all tasks are pre-completed"
    (let [started (atom [])
          plan (make-plan [(make-task :a) (make-task :b)])
          context {:pre-completed-ids #{:a :b}
                   :on-task-start #(swap! started conj %)}
          result (dag-orch/execute-plan-as-dag plan context)]
      (is (:success? result))
      (is (= 2 (:tasks-completed result)))
      (is (empty? @started) "no tasks should have been executed"))))

(deftest test-execute-plan-pre-completed-logs-resume
  (testing "logs resume info when pre-completed-ids is non-empty"
    (let [[logger entries] (log/collecting-logger)
          plan (make-plan [(make-task :a) (make-task :b)])
          context {:pre-completed-ids #{:a} :logger logger}
          _ (dag-orch/execute-plan-as-dag plan context)]
      (is (some #(= :dag/resuming (:log/event %)) @entries)))))

(deftest test-resume-preserves-artifacts-from-new-tasks
  (testing "resumed execution includes artifacts from newly executed tasks"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b)
                           (make-task :c [:a :b])])
          ;; :a pre-completed; :b and :c will execute and produce placeholder artifacts
          context {:pre-completed-ids #{:a}}
          result (dag-orch/execute-plan-as-dag plan context)]
      (is (:success? result))
      (is (= 3 (:tasks-completed result)))
      ;; Placeholder results produce empty artifact vectors per task,
      ;; but the aggregate should still be a vector (not nil)
      (is (vector? (:artifacts result))
          "artifacts should be collected as a vector"))))

;------------------------------------------------------------------------------ Layer 9
;; Failure propagation tests

(deftest test-propagate-failures
  (testing "transitively skips tasks depending on failed tasks"
    (let [tasks-map {:a {:task/deps #{}}
                     :b {:task/deps #{:a}}
                     :c {:task/deps #{:b}}
                     :d {:task/deps #{}}}
          ;; :a failed => :b and :c should be propagated, :d unaffected
          all-failed (dag-orch/propagate-failures tasks-map #{:a})]
      (is (contains? all-failed :a))
      (is (contains? all-failed :b))
      (is (contains? all-failed :c))
      (is (not (contains? all-failed :d))))))

(deftest test-compute-ready-tasks-excludes-failed
  (testing "does not return tasks whose deps have failed"
    (let [tasks-map {:a {:task/deps #{}}
                     :b {:task/deps #{:a}}}
          ;; :a failed, :b depends on :a => :b should not be ready
          ready (dag-orch/compute-ready-tasks tasks-map #{} #{:a})]
      (is (empty? ready) ":b should not be ready since :a failed"))))

(deftest test-compute-ready-tasks-returns-roots
  (testing "returns tasks with no deps when nothing is completed"
    (let [tasks-map {:a {:task/deps #{}}
                     :b {:task/deps #{}}
                     :c {:task/deps #{:a :b}}}
          ready (dag-orch/compute-ready-tasks tasks-map #{} #{})]
      (is (= #{:a :b} (set (map first ready)))))))

(deftest test-compute-ready-tasks-unlocks-dependents
  (testing "completing deps unlocks dependent tasks"
    (let [tasks-map {:a {:task/deps #{}}
                     :b {:task/deps #{:a}}
                     :c {:task/deps #{:a :b}}}
          ;; :a completed, :b should be ready, :c still blocked on :b
          ready (dag-orch/compute-ready-tasks tasks-map #{:a} #{})]
      (is (= [:b] (map first ready))))))

;------------------------------------------------------------------------------ Layer 10
;; partition-results tests

(deftest test-partition-results-mixed
  (testing "separates ok and err results"
    (let [results {:a (dag/ok {:done true})
                   :b (dag/err :failed "boom" {})
                   :c (dag/ok {:done true})}
          {:keys [completed failed]} (dag-orch/partition-results results)]
      (is (= #{:a :c} (set completed)))
      (is (= #{:b} (set failed))))))

(deftest test-partition-results-all-ok
  (testing "all ok yields no failures"
    (let [results {:a (dag/ok {}) :b (dag/ok {})}
          {:keys [completed failed]} (dag-orch/partition-results results)]
      (is (= 2 (count completed)))
      (is (empty? failed)))))

(deftest test-partition-results-all-failed
  (testing "all failed yields no completions"
    (let [results {:a (dag/err :f "e1" {}) :b (dag/err :f "e2" {})}
          {:keys [completed failed]} (dag-orch/partition-results results)]
      (is (empty? completed))
      (is (= 2 (count failed))))))

(deftest test-partition-results-empty
  (testing "empty results yield empty partitions"
    (let [{:keys [completed failed]} (dag-orch/partition-results {})]
      (is (empty? completed))
      (is (empty? failed)))))

;------------------------------------------------------------------------------ Layer 11
;; aggregate-results tests

(deftest test-aggregate-results-accumulates-metrics
  (testing "sums tokens, cost, and duration across results"
    (let [results {:a (dag/ok {:artifacts [{:id 1}]
                               :metrics {:tokens 100 :cost-usd 0.5 :duration-ms 1000}})
                   :b (dag/ok {:artifacts [{:id 2} {:id 3}]
                               :metrics {:tokens 200 :cost-usd 1.0 :duration-ms 2000}})}
          agg (dag-orch/aggregate-results results)]
      (is (= 3 (count (:artifacts agg))))
      (is (= 300 (:total-tokens agg)))
      (is (= 1.5 (:total-cost agg)))
      (is (= 3000 (:total-duration agg))))))

(deftest test-aggregate-results-defaults-missing-metrics
  (testing "defaults to zero when metrics are absent"
    (let [results {:a (dag/ok {:artifacts []})
                   :b (dag/ok {})}
          agg (dag-orch/aggregate-results results)]
      (is (= 0 (:total-tokens agg)))
      (is (= 0.0 (:total-cost agg)))
      (is (= 0 (:total-duration agg))))))

(deftest test-aggregate-results-empty
  (testing "empty results yield zero aggregates"
    (let [agg (dag-orch/aggregate-results {})]
      (is (= 0 (:total-tokens agg)))
      (is (empty? (:artifacts agg))))))

;------------------------------------------------------------------------------ Layer 12
;; Additional propagate-failures scenarios

(deftest test-propagate-failures-diamond
  (testing "diamond: failure at root cascades to all dependents"
    (let [tasks-map {:a {:task/deps #{}}
                     :b {:task/deps #{:a}}
                     :c {:task/deps #{:a}}
                     :d {:task/deps #{:b :c}}}
          all-failed (dag-orch/propagate-failures tasks-map #{:a})]
      (is (= #{:a :b :c :d} all-failed)))))

(deftest test-propagate-failures-no-deps
  (testing "independent tasks: failure in one doesn't affect others"
    (let [tasks-map {:a {:task/deps #{}}
                     :b {:task/deps #{}}
                     :c {:task/deps #{}}}
          all-failed (dag-orch/propagate-failures tasks-map #{:a})]
      (is (= #{:a} all-failed)))))

(deftest test-propagate-failures-empty
  (testing "no failures yields empty set"
    (let [tasks-map {:a {:task/deps #{}} :b {:task/deps #{:a}}}
          all-failed (dag-orch/propagate-failures tasks-map #{})]
      (is (empty? all-failed)))))

(deftest test-propagate-failures-partial-deps
  (testing "task with one failed dep and one ok dep still fails"
    (let [tasks-map {:a {:task/deps #{}}
                     :b {:task/deps #{}}
                     :c {:task/deps #{:a :b}}}
          ;; :a failed but :b did not — :c still transitively fails
          all-failed (dag-orch/propagate-failures tasks-map #{:a})]
      (is (contains? all-failed :c) ":c depends on :a which failed"))))

;------------------------------------------------------------------------------ Layer 13
;; emit-batch-events! tests

(deftest test-emit-batch-events-nil-stream
  (testing "does not throw with nil event stream"
    (is (nil? (dag-orch/emit-batch-events!
               {:a (dag/ok {}) :b (dag/err :f "err" {})}
               nil "wf-1")))))

;------------------------------------------------------------------------------ Layer 14
;; Pre-completed with dependency chains

(deftest test-pre-completed-with-dep-chain
  (testing "pre-completing middle of chain unlocks downstream"
    (let [started (atom [])
          plan (make-plan [(make-task :a)
                           (make-task :b [:a])
                           (make-task :c [:b])])
          ;; :a and :b both pre-completed, only :c should run
          context {:pre-completed-ids #{:a :b}
                   :on-task-start #(swap! started conj %)}
          result (dag-orch/execute-plan-as-dag plan context)]
      (is (:success? result))
      (is (= 3 (:tasks-completed result)))
      (is (= [:c] @started) "only :c should have executed"))))

(deftest test-unreached-tasks-tracked
  (testing "unreached count is correct when failures block tasks"
    ;; This test verifies that the :tasks-unreached field is populated.
    ;; With placeholder execution (no llm-backend), all tasks succeed,
    ;; so unreached should be 0.
    (let [plan (make-plan [(make-task :a)
                           (make-task :b [:a])
                           (make-task :c [:a])])
          result (dag-orch/execute-plan-as-dag plan {})]
      (is (= 0 (:tasks-unreached result))))))
