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
(ns ai.miniforge.cli.main.commands.plan-executor-pipeline-test
  "`mf run plan.edn` with an LLM backend runs each task as a sub-workflow
   through the context's `:execution/run-pipeline-fn`. Without it every
   task fails before any work starts."
  (:require
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.cli.main.commands.plan-executor :as plan-executor]
   [ai.miniforge.cli.main.display :as main-display]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.workflow.interface :as workflow]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} plan-with-tasks
  [n]
  {:plan/id "plan-pipeline-test"
   :plan/title "plan pipeline test"
   :plan/tasks (vec (for [i (range n)]
                      {:task/id (random-uuid)
                       :task/description (str "task " i)
                       :task/dependencies #{}
                       :task/acceptance-criteria []
                       :task/type :implement}))})

(defn- ^{:stratum 0} stub-workflow-context
  "The parts of `create-workflow-context` the DAG reads, without the
   event publication, git probe, and worktree lookup."
  [{:keys [callbacks event-stream llm-client control-state]}]
  (assoc callbacks
         :llm-backend llm-client
         :event-stream event-stream
         :control-state control-state))

(defn- ^{:stratum 0} completed-sub-workflow
  "Stub pipeline: records each call and returns a completed run."
  [calls]
  (fn [sub-workflow _input _opts]
    (swap! calls conj (:workflow/id sub-workflow))
    {:execution/status :completed
     :execution/artifacts [{:artifact/id (random-uuid)}]
     :execution/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} plan-run-executes-tasks-through-the-run-pipeline-test
  (testing "with an LLM backend configured, each task runs as a sub-workflow"
    (let [calls (atom [])
          stream (es/create-event-stream {:sinks []})]
      (with-redefs [workflow/run-pipeline (completed-sub-workflow calls)
                    es/create-event-stream (fn [& _] stream)
                    supervisory/attach! (constantly nil)
                    correlator/attach! (constantly nil)
                    context/create-llm-client (constantly :llm-client)
                    context/create-workflow-context stub-workflow-context
                    main-display/print-info (constantly nil)
                    main-display/print-error (constantly nil)
                    control/register-workflow-control! (constantly nil)
                    control/release-workflow-control! (constantly nil)]
        (let [result (plan-executor/execute-plan (plan-with-tasks 2) {:quiet true})]
          (is (true? (:success? result)))
          (is (= 2 (:tasks-completed result)))
          (is (zero? (:tasks-failed result 0)))
          (is (= 2 (count @calls)) "each task ran through the pipeline fn"))))))
