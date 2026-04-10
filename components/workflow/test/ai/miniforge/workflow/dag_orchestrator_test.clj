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
  "Tests for DAG orchestrator: stratum wiring, conflict-aware batching,
   plan-to-DAG conversion with new decomposition fields."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def id-a (random-uuid))
(def id-b (random-uuid))
(def id-c (random-uuid))
(def id-d (random-uuid))

;------------------------------------------------------------------------------ Layer 1
;; wire-stratum-deps tests

(deftest wire-stratum-deps-no-strata-test
  (testing "no-op when no tasks have :task/stratum"
    (let [tasks [{:task/id id-a :task/deps #{}}
                 {:task/id id-b :task/deps #{}}]]
      (is (= tasks (dag-orch/wire-stratum-deps tasks))))))

(deftest wire-stratum-deps-wires-across-strata-test
  (testing "stratum-1 tasks auto-depend on all stratum-0 tasks"
    (let [tasks [{:task/id id-a :task/deps #{} :task/stratum 0}
                 {:task/id id-b :task/deps #{} :task/stratum 0}
                 {:task/id id-c :task/deps #{} :task/stratum 1}]
          result (dag-orch/wire-stratum-deps tasks)
          c-deps (:task/deps (nth result 2))]
      (is (= #{id-a id-b} c-deps)))))

(deftest wire-stratum-deps-preserves-explicit-deps-test
  (testing "tasks with explicit deps are not overwritten"
    (let [tasks [{:task/id id-a :task/deps #{} :task/stratum 0}
                 {:task/id id-b :task/deps #{id-a} :task/stratum 1}]
          result (dag-orch/wire-stratum-deps tasks)
          b-deps (:task/deps (nth result 1))]
      (is (= #{id-a} b-deps)))))

(deftest wire-stratum-deps-three-strata-test
  (testing "stratum-2 depends on stratum-1, not stratum-0"
    (let [tasks [{:task/id id-a :task/deps #{} :task/stratum 0}
                 {:task/id id-b :task/deps #{} :task/stratum 1}
                 {:task/id id-c :task/deps #{} :task/stratum 2}]
          result (dag-orch/wire-stratum-deps tasks)]
      (is (= #{id-a} (:task/deps (nth result 1))))
      (is (= #{id-b} (:task/deps (nth result 2)))))))

;------------------------------------------------------------------------------ Layer 2
;; select-non-conflicting-batch tests

(deftest select-non-conflicting-batch-no-files-test
  (testing "selects all when no exclusive-files declared"
    (let [tasks [[id-a {:task/id id-a}]
                 [id-b {:task/id id-b}]
                 [id-c {:task/id id-c}]]
          batch (dag-orch/select-non-conflicting-batch tasks 4)]
      (is (= 3 (count batch))))))

(deftest select-non-conflicting-batch-respects-max-test
  (testing "respects max-parallel limit"
    (let [tasks [[id-a {:task/id id-a}]
                 [id-b {:task/id id-b}]
                 [id-c {:task/id id-c}]]
          batch (dag-orch/select-non-conflicting-batch tasks 2)]
      (is (= 2 (count batch))))))

(deftest select-non-conflicting-batch-skips-conflicts-test
  (testing "skips tasks with overlapping exclusive-files"
    (let [tasks [[id-a {:task/id id-a
                        :task/exclusive-files ["src/foo.clj" "src/bar.clj"]}]
                 [id-b {:task/id id-b
                        :task/exclusive-files ["src/bar.clj" "src/baz.clj"]}]
                 [id-c {:task/id id-c
                        :task/exclusive-files ["src/qux.clj"]}]]
          batch (dag-orch/select-non-conflicting-batch tasks 4)
          selected-ids (set (map first batch))]
      ;; a and c should be selected; b conflicts with a on src/bar.clj
      (is (contains? selected-ids id-a))
      (is (not (contains? selected-ids id-b)))
      (is (contains? selected-ids id-c)))))

(deftest select-non-conflicting-batch-mixed-declared-test
  (testing "tasks without exclusive-files don't conflict with anything"
    (let [tasks [[id-a {:task/id id-a
                        :task/exclusive-files ["src/foo.clj"]}]
                 [id-b {:task/id id-b}]
                 [id-c {:task/id id-c
                        :task/exclusive-files ["src/foo.clj"]}]]
          batch (dag-orch/select-non-conflicting-batch tasks 4)
          selected-ids (set (map first batch))]
      ;; a and b selected; c conflicts with a
      (is (contains? selected-ids id-a))
      (is (contains? selected-ids id-b))
      (is (not (contains? selected-ids id-c))))))

;------------------------------------------------------------------------------ Layer 3
;; plan->dag-tasks integration tests

(deftest plan-to-dag-tasks-forwards-new-fields-test
  (testing "component and exclusive-files are forwarded to DAG tasks"
    (let [plan {:plan/id (random-uuid)
                :plan/name "test"
                :plan/tasks [{:task/id id-a
                              :task/description "Agent work"
                              :task/type :implement
                              :task/component "agent"
                              :task/exclusive-files ["components/agent/src/foo.clj"]
                              :task/stratum 0}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          task (first dag-tasks)]
      (is (= "agent" (:task/component task)))
      (is (= ["components/agent/src/foo.clj"] (:task/exclusive-files task)))
      (is (= 0 (:task/stratum task))))))

(deftest plan-to-dag-tasks-stratum-wiring-integration-test
  (testing "stratum deps are auto-wired during plan->dag-tasks conversion"
    (let [plan {:plan/id (random-uuid)
                :plan/name "multi-stratum"
                :plan/tasks [{:task/id id-a
                              :task/description "Foundation"
                              :task/type :implement
                              :task/stratum 0}
                             {:task/id id-b
                              :task/description "Depends on foundation"
                              :task/type :implement
                              :task/stratum 1}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          task-b (second dag-tasks)]
      (is (contains? (:task/deps task-b) id-a)))))

(deftest plan-to-dag-tasks-backward-compat-test
  (testing "plan without new fields still converts correctly"
    (let [plan {:plan/id (random-uuid)
                :plan/name "old-style"
                :plan/tasks [{:task/id id-a
                              :task/description "Single task"
                              :task/type :implement}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          task (first dag-tasks)]
      (is (= id-a (:task/id task)))
      (is (nil? (:task/component task)))
      (is (nil? (:task/exclusive-files task))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.dag-orchestrator-test)

  :leave-this-here)
