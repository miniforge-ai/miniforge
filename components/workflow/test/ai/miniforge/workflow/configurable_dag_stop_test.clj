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
(ns ai.miniforge.workflow.configurable-dag-stop-test
  "A configurable workflow whose plan runs through the DAG records an
   operator cancel as the same :dashboard-stop entry the runner and
   `execution-dag` write, not as a generic DAG failure."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.configurable :as configurable]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.messages :as messages]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private plan-then-done
  {:workflow/id :configurable-dag-stop
   :workflow/version "1.0.0"
   :workflow/phases [{:phase/id :plan
                      :phase/name "Plan"
                      :phase/handler :test/plan
                      :phase/next [{:target :done}]}
                     {:phase/id :done
                      :phase/name "Done"
                      :phase/agent :none
                      :phase/next []}]
   :workflow/entry-phase :plan})

(defn- ^{:stratum 0} plan-handler
  "Plan phase that succeeds with a plan of two independent tasks, so the
   plan is parallelizable and goes to the DAG."
  [_phase-config _exec-state _execution-context]
  {:success? true
   :artifacts [{:plan/id (random-uuid)
                :plan/name "two independent tasks"
                :plan/tasks [{:task/id (random-uuid)
                              :task/description "left"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id (random-uuid)
                              :task/description "right"
                              :task/type :implement
                              :task/dependencies []}]}]
   :errors []
   :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}})

(defn- ^{:stratum 0} errors-of-type
  [exec-state error-type]
  (filterv #(= error-type (:type %)) (:execution/errors exec-state)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-with-context
  [execution-context]
  (configurable/run-configurable-workflow
   plan-then-done
   {:task "configurable DAG stop"}
   (assoc execution-context :phase-handlers {:test/plan plan-handler})))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} cancelled-dag-records-dashboard-stop-test
  (testing "a cancel that reaches the DAG is recorded as :dashboard-stop"
    (let [control (es/create-control-state)
          _ (es/cancel! control)
          exec-state (run-with-context {:control-state control})
          [stop :as stops] (errors-of-type exec-state :dashboard-stop)]
      (is (true? (get-in exec-state [:execution/dag-result :cancelled?]))
          "the DAG saw the cancel")
      (is (phase/failed? exec-state))
      (is (= 1 (count stops)))
      (is (= (messages/t :status/stopped-control) (:message stop)))
      (is (= {:reason :dashboard-stop} (:data stop)))
      (is (empty? (errors-of-type exec-state :dag-execution-failed))
          "a cancel is not reported as a DAG failure"))))

(deftest ^{:stratum 2} failed-dag-still-records-dag-execution-failed-test
  (testing "a DAG that fails without a cancel keeps :dag-execution-failed"
    (with-redefs [dag-orch/execute-plan-as-dag (fn [_plan _context]
                                                 {:success? false
                                                  :tasks-failed 1})]
      (let [exec-state (run-with-context {})]
        (is (phase/failed? exec-state))
        (is (= 1 (count (errors-of-type exec-state :dag-execution-failed))))
        (is (empty? (errors-of-type exec-state :dashboard-stop)))))))
