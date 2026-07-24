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

(ns ai.miniforge.cli.workflow-runner.runner-control-wiring-test
  "Phase D D-4: every CLI runner path joins the governed control path.

   D-3 wired the spec runner only; the chain runner and the plan
   executor created a control-state that nothing could ever reach.
   These tests pin that each path registers its live handles before
   work starts and releases them in the `finally` — including when the
   run throws, since a dead runner that still claims interventions is
   the paint-on-glass failure Phase D exists to remove."
  (:require
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.cli.main.commands.plan-executor :as plan-executor]
   [ai.miniforge.cli.main.display :as main-display]
   [ai.miniforge.cli.workflow-runner :as runner]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.workflow.interface :as workflow]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Recording harness

(defn- recorder
  "Redef map that records registration/release calls into `calls`."
  [calls]
  {:register (fn [workflow-id control-state event-stream]
               (swap! calls conj {:call :register
                                  :workflow-id workflow-id
                                  :control-state control-state
                                  :event-stream event-stream})
               nil)
   :release (fn [workflow-id]
              (swap! calls conj {:call :release :workflow-id workflow-id})
              nil)})

(defn- calls-of
  [calls call-type]
  (filterv #(= call-type (:call %)) @calls))

(defn- registered-and-released?
  "The same workflow id was registered once and released once."
  [calls]
  (let [registered (mapv :workflow-id (calls-of calls :register))
        released (mapv :workflow-id (calls-of calls :release))]
    (and (= 1 (count registered))
         (= registered released))))

;------------------------------------------------------------------------------ Layer 1
;; Chain runner

(deftest chain-runner-registers-and-releases-control
  (testing "run-chain! joins the governed control path"
    (let [calls (atom [])
          {:keys [register release]} (recorder calls)
          stream (es/create-event-stream {:sinks []})]
      (with-redefs [workflow/load-chain (fn [_id _version]
                                          {:chain {:chain/description "d"
                                                   :chain/steps []}})
                    workflow/run-chain (fn [_def _input _ctx]
                                         {:execution/status :completed})
                    es/create-event-stream (fn [& _] stream)
                    supervisory/attach! (constantly nil)
                    correlator/attach! (constantly nil)
                    context/create-llm-client (constantly :llm-client)
                    context/create-workflow-context identity
                    llm/client-backend (constantly nil)
                    display/start-progress! (constantly (fn [] nil))
                    dashboard/print-dashboard-status! (constantly nil)
                    control/register-workflow-control! register
                    control/release-workflow-control! release]
        (runner/run-chain! :some-chain {:quiet true})
        (is (registered-and-released? calls))
        (let [registration (first (calls-of calls :register))]
          (is (uuid? (:workflow-id registration)))
          (is (identical? stream (:event-stream registration)))
          (is (some? (:control-state registration))))))))

(deftest chain-runner-releases-control-when-the-chain-throws
  (testing "a failed chain still stops claiming interventions"
    (let [calls (atom [])
          {:keys [register release]} (recorder calls)
          stream (es/create-event-stream {:sinks []})]
      (with-redefs [workflow/load-chain (fn [_id _version]
                                          {:chain {:chain/description "d"
                                                   :chain/steps []}})
                    workflow/run-chain (fn [_def _input _ctx]
                                         (throw (ex-info "chain blew up" {})))
                    es/create-event-stream (fn [& _] stream)
                    supervisory/attach! (constantly nil)
                    correlator/attach! (constantly nil)
                    context/create-llm-client (constantly :llm-client)
                    context/create-workflow-context identity
                    llm/client-backend (constantly nil)
                    display/start-progress! (constantly (fn [] nil))
                    dashboard/print-dashboard-status! (constantly nil)
                    control/register-workflow-control! register
                    control/release-workflow-control! release]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"chain blew up"
                              (runner/run-chain! :some-chain {:quiet true})))
        (is (registered-and-released? calls))))))

;------------------------------------------------------------------------------ Layer 2
;; Plan executor

(deftest plan-executor-registers-and-releases-control
  (testing "execute-plan joins the governed control path"
    (let [calls (atom [])
          {:keys [register release]} (recorder calls)
          stream (es/create-event-stream {:sinks []})]
      (with-redefs [workflow/execute-plan-as-dag (fn [_plan _ctx]
                                                   {:tasks-completed 1})
                    es/create-event-stream (fn [& _] stream)
                    supervisory/attach! (constantly nil)
                    correlator/attach! (constantly nil)
                    context/create-llm-client (constantly :llm-client)
                    context/create-workflow-context identity
                    main-display/print-info (constantly nil)
                    control/register-workflow-control! register
                    control/release-workflow-control! release]
        (plan-executor/execute-plan {:plan/id "p1" :plan/tasks []} {:quiet true})
        (is (registered-and-released? calls))
        (let [registration (first (calls-of calls :register))]
          (is (identical? stream (:event-stream registration)))
          (is (some? (:control-state registration))))))))

(deftest plan-executor-releases-control-when-the-plan-throws
  (testing "a failed plan still stops claiming interventions"
    (let [calls (atom [])
          {:keys [register release]} (recorder calls)
          stream (es/create-event-stream {:sinks []})]
      (with-redefs [workflow/execute-plan-as-dag (fn [_plan _ctx]
                                                   (throw (ex-info "plan blew up" {})))
                    es/create-event-stream (fn [& _] stream)
                    supervisory/attach! (constantly nil)
                    correlator/attach! (constantly nil)
                    context/create-llm-client (constantly :llm-client)
                    context/create-workflow-context identity
                    main-display/print-info (constantly nil)
                    main-display/print-error (constantly nil)
                    control/register-workflow-control! register
                    control/release-workflow-control! release]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plan blew up"
                              (plan-executor/execute-plan
                               {:plan/id "p1" :plan/tasks []} {:quiet true})))
        (is (registered-and-released? calls))))))
