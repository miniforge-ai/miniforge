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

(ns ai.miniforge.workflow.dag-activation-diagnostics-test
  "Unit tests for DAG activation skip-reason diagnostics.

   Ensures every non-activation path has a specific reason that will land
   in the event log via :workflow/dag-considered."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.execution :as exec]))

(def ^:private valid-plan
  {:plan/id #uuid "00000000-0000-0000-0000-000000000001"
   :plan/tasks [{:task/id #uuid "00000000-0000-0000-0000-000000000002"
                 :task/description "t1"}
                {:task/id #uuid "00000000-0000-0000-0000-000000000003"
                 :task/description "t2"}]
   :plan/name "test"})

(defn- phase-result-with-plan [plan]
  {:name :plan
   :agent :planner
   :result {:status :success :output plan}})

(deftest skip-reason-not-plan-phase
  (testing "Non-plan phases skip with :not-plan-phase"
    (is (= :not-plan-phase
           (exec/dag-skip-reason :implement (phase-result-with-plan valid-plan) {})))
    (is (= :not-plan-phase
           (exec/dag-skip-reason :verify {} {})))))

(deftest skip-reason-disabled
  (testing "When ctx has :disable-dag-execution, skip with :disabled"
    (is (= :disabled
           (exec/dag-skip-reason :plan
                                 (phase-result-with-plan valid-plan)
                                 {:disable-dag-execution true})))))

(deftest skip-reason-no-plan-id
  (testing "Missing :plan/id in phase output skips with :no-plan-id"
    (is (= :no-plan-id
           (exec/dag-skip-reason :plan {:result {:status :success :output {}}} {})))
    (is (= :no-plan-id
           (exec/dag-skip-reason :plan {:result {:status :error}} {})))
    (is (= :no-plan-id
           (exec/dag-skip-reason :plan {} {})))))

(deftest skip-reason-no-tasks
  (testing "Plan with :plan/id but empty :plan/tasks skips with :no-tasks"
    (let [empty-plan {:plan/id (random-uuid) :plan/tasks []}]
      (is (= :no-tasks
             (exec/dag-skip-reason :plan (phase-result-with-plan empty-plan) {}))))))

(deftest activates-with-tasks
  (testing "Plan with :plan/id and at least one task returns nil (don't skip)"
    (is (nil? (exec/dag-skip-reason :plan (phase-result-with-plan valid-plan) {})))
    (let [one-task-plan {:plan/id (random-uuid)
                         :plan/tasks [{:task/id (random-uuid)}]}]
      (is (nil? (exec/dag-skip-reason :plan
                                      (phase-result-with-plan one-task-plan)
                                      {}))))))

(deftest dag-applicable-backward-compatible
  (testing "dag-applicable? preserves existing return semantics"
    (is (= valid-plan
           (exec/dag-applicable? :plan (phase-result-with-plan valid-plan) {})))
    (is (nil? (exec/dag-applicable? :implement (phase-result-with-plan valid-plan) {})))
    (is (nil? (exec/dag-applicable? :plan
                                    (phase-result-with-plan valid-plan)
                                    {:disable-dag-execution true})))))
