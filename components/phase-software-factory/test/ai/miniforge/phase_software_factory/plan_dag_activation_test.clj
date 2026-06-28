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

(ns ai.miniforge.phase-software-factory.plan-dag-activation-test
  "Integration tests for the plan→DAG wiring path.

   Spec: plan-from-agent-dag-wiring (GROUP 4)

   Verifies that:
   1. A valid agent result (success + non-empty :plan/tasks) passes through
      validate-dag-readiness unchanged — DAG will activate downstream.
   2. A zero-task success is converted to an explicit failure with
      :anomalies.dag/no-tasks — no silent monolithic fallback.
   3. A plan with unknown dep refs fails with :anomalies.dag/unknown-deps.
   4. :already-satisfied and error/failure results pass through untouched."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase-software-factory.plan :as plan]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Fixtures

(def ^:private task-a-id #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private task-b-id #uuid "00000000-0000-0000-0000-000000000003")

(def ^:private valid-plan-output
  "A minimal valid plan the planner would produce for a 2-task spec."
  {:plan/id    #uuid "00000000-0000-0000-0000-000000000001"
   :plan/name  "test-plan"
   :plan/tasks [{:task/id          task-a-id
                 :task/description "Implement feature"
                 :task/type        :implement}
                {:task/id           task-b-id
                 :task/description  "Test feature"
                 :task/type         :test
                 :task/dependencies [task-a-id]}]})

;------------------------------------------------------------------------------ GROUP 4 tests: validate-dag-readiness

(deftest valid-plan-passes-through-test
  (testing "Valid success result with non-empty tasks passes through unchanged"
    (let [input  (response/success valid-plan-output {:tokens 100})
          result (#'plan/validate-dag-readiness input)]
      (is (= :success (:status result))
          "valid plan must keep :success status")
      (is (= valid-plan-output (:output result))
          "valid plan output must be preserved exactly"))))

(deftest empty-task-plan-fails-explicitly-test
  (testing "Zero-task success is converted to a failure, not a silent DAG skip"
    (let [empty-plan {:plan/id (random-uuid) :plan/name "empty" :plan/tasks []}
          input  (response/success empty-plan {:tokens 100})
          result (#'plan/validate-dag-readiness input)]
      (is (= :error (:status result))
          "zero-task plan must fail with :error, not silently trigger :no-tasks DAG skip")
      (is (= :anomalies.dag/no-tasks
             (get-in result [:error :data :anomaly]))
          "failure must carry :anomalies.dag/no-tasks anomaly for observability")
      (is (= 100 (get-in result [:metrics :tokens]))
          "failure must preserve planner invocation metrics"))))

(deftest unknown-dep-refs-fail-explicitly-test
  (testing "Tasks referencing non-existent task IDs fail with :anomalies.dag/unknown-deps"
    (let [ghost-id (random-uuid)
          bad-plan {:plan/id    (random-uuid)
                    :plan/name  "bad-deps"
                    :plan/tasks [{:task/id          (random-uuid)
                                  :task/description "orphan task"
                                  :task/type        :implement
                                  :task/dependencies [ghost-id]}]}
          input  (response/success bad-plan {:tokens 100})
          result (#'plan/validate-dag-readiness input)]
      (is (= :error (:status result))
          "unknown dep refs must fail — not silently become orphan tasks at runtime")
      (is (= :anomalies.dag/unknown-deps
             (get-in result [:error :data :anomaly]))
          "failure must carry :anomalies.dag/unknown-deps anomaly")
      (is (= 100 (get-in result [:metrics :tokens]))
          "failure must preserve planner invocation metrics"))))

(deftest valid-deps-pass-through-test
  (testing "Tasks with dependencies pointing to known task IDs pass through"
    (let [input  (response/success valid-plan-output {:tokens 100})
          result (#'plan/validate-dag-readiness input)]
      (is (= :success (:status result))
          "known dep refs (task-b depends on task-a) must not trigger validation error"))))

(deftest already-satisfied-passes-through-test
  (testing ":already-satisfied results pass through validate-dag-readiness unchanged"
    (let [already-sat (assoc (response/success {:plan/id    (random-uuid)
                                                :plan/tasks []} {:tokens 50})
                             :status :already-satisfied)
          result (#'plan/validate-dag-readiness already-sat)]
      (is (= :already-satisfied (:status result))
          ":already-satisfied must not be touched — empty tasks is correct for that path"))))

(deftest error-result-passes-through-test
  (testing "Error results pass through validate-dag-readiness without modification"
    (let [error-result {:status :error :error {:message "LLM failed"}}
          result (#'plan/validate-dag-readiness error-result)]
      (is (= :error (:status result))
          "error results must pass through — phase FSM handles them via extract-status"))))

(deftest failure-result-passes-through-test
  (testing "Non-success results (e.g. :failed) pass through validate-dag-readiness unchanged"
    (let [failure-result {:status :failed :error {:message "timeout"}}
          result (#'plan/validate-dag-readiness failure-result)]
      (is (= :failed (:status result))
          "non-success results must pass through — phase FSM handles them via extract-status"))))
