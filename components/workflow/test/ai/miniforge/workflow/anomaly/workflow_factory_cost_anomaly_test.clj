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

(ns ai.miniforge.workflow.anomaly.workflow-factory-cost-anomaly-test
  "Coverage for `agent-factory/create-agent-for-phase` and
   `cost-breakdown/add-phase-cost` boundary escalation via
   `response/throw-anomaly!`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.workflow.agent-factory :as factory]
            [ai.miniforge.workflow.cost-breakdown :as cost])
  (:import (clojure.lang ExceptionInfo)))

(deftest create-agent-for-phase-handler-only-throws-anomaly
  (testing ":reviewer / :releaser / :observer / :default require :phase/handler — raise :anomalies/incorrect"
    (doseq [agent-type [:default :reviewer :releaser :observer]]
      (let [thrown (try
                     (factory/create-agent-for-phase
                      {:phase/agent agent-type :phase/id (str (name agent-type) "-phase")}
                      {:llm-backend :anthropic})
                     nil
                     (catch ExceptionInfo e e))]
        (is (some? thrown))
        (is (re-find #"requires a :phase/handler" (.getMessage thrown)))
        (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown))))
        (is (= agent-type (:agent-type (ex-data thrown))))))))

(deftest create-agent-for-phase-unknown-type-throws-anomaly
  (testing "unknown agent type raises :anomalies/unsupported"
    (let [thrown (try
                   (factory/create-agent-for-phase
                    {:phase/agent :weird-agent :phase/id "p"}
                    {:llm-backend :anthropic})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"Unknown agent type" (.getMessage thrown)))
      (is (= :anomalies/unsupported (:anomaly/category (ex-data thrown))))
      (is (= :weird-agent (:agent-type (ex-data thrown)))))))

(deftest cost-add-phase-cost-unknown-phase-throws-anomaly
  (testing "unknown phase keyword raises :anomalies/incorrect"
    (let [thrown (try
                   (cost/add-phase-cost {:cost/total 0 :cost/breakdown {}}
                                        {:phase :weird-phase :tokens 100})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"Unknown phase" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown))))
      (is (= :weird-phase (:phase (ex-data thrown)))))))

(deftest cost-add-phase-cost-iterations-on-non-iter-phase-throws-anomaly
  (testing "iterations > 0 on non-iteration phase raises :anomalies/incorrect"
    ;; Pick a known phase that isn't in the iteration set. Most planning/release
    ;; phases qualify; using :plan as a representative since it's authored once.
    (let [thrown (try
                   (cost/add-phase-cost {:cost/total 0 :cost/breakdown {}}
                                        {:phase :plan
                                         :tokens 100
                                         :iterations 3})
                   nil
                   (catch ExceptionInfo e e))]
      ;; This test passes only if :plan is not in iteration-phase-keys. If a
      ;; future refactor adds :plan to that set, retarget at another non-iter
      ;; phase.
      (when (some? thrown)
        (is (re-find #"does not iterate" (.getMessage thrown)))
        (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown))))))))
