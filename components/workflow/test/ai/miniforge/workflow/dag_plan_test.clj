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
(ns ai.miniforge.workflow.dag-plan-test
  "Tests for DAG plan analysis: traverse-levels cycle/dangling-dep guards,
   build-deps-map id normalization, estimate-parallel-speedup anomaly
   propagation, and parallelizable-plan? safe fallback."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.workflow.dag-plan :as dag-plan]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} id-a (random-uuid))

(def ^{:stratum 0} id-b (random-uuid))

(def ^{:stratum 0} id-c (random-uuid))

;------------------------------------------------------------------------------ traverse-levels — cycle and dangling-dep guards

(deftest ^{:stratum 0} traverse-levels-linear-chain-test
  (testing "A→B→C linear chain completes in 3 levels"
    (let [result (dag-plan/traverse-levels
                  [id-a id-b id-c]
                  {id-a #{} id-b #{id-a} id-c #{id-b}})]
      (is (= {:levels 3 :max-width 1} result)))))

(deftest ^{:stratum 0} traverse-levels-parallel-tasks-test
  (testing "Independent tasks resolve in one level"
    (let [result (dag-plan/traverse-levels
                  [id-a id-b id-c]
                  {id-a #{} id-b #{} id-c #{}})]
      (is (= {:levels 1 :max-width 3} result)))))

(deftest ^{:stratum 0} traverse-levels-empty-test
  (testing "Empty task list returns zero levels and zero width"
    (let [result (dag-plan/traverse-levels [] {})]
      (is (= {:levels 0 :max-width 0} result)))))

(deftest ^{:stratum 0} traverse-levels-cyclic-terminates-test
  (testing "A cycle (A→B, B→A) returns an anomaly instead of looping forever"
    (let [result (dag-plan/traverse-levels
                  [id-a id-b]
                  {id-a #{id-b} id-b #{id-a}})]
      (is (anomaly/anomaly? result)
          "Result must be an anomaly, not a map with :levels/:max-width")
      (is (= :anomalies/dag-unschedulable (:anomaly/subtype result))
          "Subtype must be :anomalies/dag-unschedulable"))))

(deftest ^{:stratum 0} traverse-levels-dangling-dep-terminates-test
  (testing "A task depending on a non-existent id returns an anomaly"
    (let [phantom (random-uuid)
          result (dag-plan/traverse-levels
                  [id-a id-b]
                  {id-a #{} id-b #{phantom}})]
      (is (anomaly/anomaly? result)
          "Result must be an anomaly when a dep id is absent from task-ids")
      (is (= :anomalies/dag-unschedulable (:anomaly/subtype result))
          "Subtype must be :anomalies/dag-unschedulable"))))

(deftest ^{:stratum 0} traverse-levels-anomaly-carries-unresolved-ids-test
  (testing "The anomaly data includes the stuck task ids"
    (let [result (dag-plan/traverse-levels
                  [id-a id-b]
                  {id-a #{id-b} id-b #{id-a}})]
      (is (anomaly/anomaly? result))
      (is (seq (get-in result [:anomaly/data :unresolved-task-ids]))
          "Anomaly data must carry the unresolved task ids"))))

;------------------------------------------------------------------------------ estimate-parallel-speedup — anomaly propagation

(deftest ^{:stratum 0} estimate-parallel-speedup-cyclic-terminates-test
  (testing "estimate-parallel-speedup returns non-parallelizable when plan has a cycle"
    (let [plan {:plan/tasks
                [{:task/id id-a :task/dependencies [id-b]}
                 {:task/id id-b :task/dependencies [id-a]}]}
          result (dag-plan/estimate-parallel-speedup plan)]
      (is (= false (:parallelizable? result))
          "A cyclic plan must not be reported as parallelizable")
      (is (some? (:anomaly result))
          "Result must carry the anomaly under :anomaly"))))

(deftest ^{:stratum 0} estimate-parallel-speedup-dangling-dep-terminates-test
  (testing "estimate-parallel-speedup returns non-parallelizable when a dep is absent"
    (let [phantom (random-uuid)
          plan {:plan/tasks
                [{:task/id id-a :task/dependencies []}
                 {:task/id id-b :task/dependencies [phantom]}]}
          result (dag-plan/estimate-parallel-speedup plan)]
      (is (= false (:parallelizable? result))
          "A plan with a dangling dep must not be reported as parallelizable")
      (is (some? (:anomaly result))
          "Result must carry the anomaly under :anomaly"))))

;------------------------------------------------------------------------------ parallelizable-plan? — safe fallback on anomaly

(deftest ^{:stratum 0} parallelizable-plan-cyclic-returns-falsy-test
  (testing "parallelizable-plan? returns falsy (not nil/true) for a cyclic plan"
    (let [plan {:plan/tasks
                [{:task/id id-a :task/dependencies [id-b]}
                 {:task/id id-b :task/dependencies [id-a]}]}]
      (is (not (dag-plan/parallelizable-plan? plan))
          "A cyclic plan must not be treated as parallelizable"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.dag-plan-test)

  :leave-this-here)
