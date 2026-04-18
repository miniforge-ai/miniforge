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

;;----------------------------------------------------------------------------
;; dag-skip-diagnostic — keys-only snapshot of what the plan phase returned

(deftest diagnostic-success-output-missing-plan-id
  (testing "planner succeeded but :output lacks :plan/id (the observed case)"
    (let [pr {:name :plan :result {:status :success :output {:some/other-key 1}}}
          d  (exec/dag-skip-diagnostic pr :no-plan-id)]
      (is (= :success (:result/status d)))
      (is (= :map (:output/type d)))
      (is (= [:some/other-key] (:output/keys d)))
      (is (false? (:output/has-plan-id? d)))
      (is (nil? (:plan/task-count d))
          ":no-plan-id reason should not include :plan/task-count"))))

(deftest diagnostic-output-is-nil
  (testing "planner succeeded but :output is nil"
    (let [pr {:name :plan :result {:status :success :output nil}}
          d  (exec/dag-skip-diagnostic pr :no-plan-id)]
      (is (= :nil (:output/type d)))
      (is (nil? (:output/keys d))
          "no :output/keys when output is not a map")
      (is (nil? (:output/has-plan-id? d))
          "no :output/has-plan-id? when output is not a map"))))

(deftest diagnostic-result-is-failure
  (testing "planner failed — :result has no :output at all"
    (let [pr {:name :plan :result {:status :failure :error {:message "boom"}}}
          d  (exec/dag-skip-diagnostic pr :no-plan-id)]
      (is (= :failure (:result/status d)))
      (is (= :nil (:output/type d)))
      (is (some #{:status :error} (:result/keys d))))))

(deftest diagnostic-surfaces-result-error-on-error-status
  (testing "error status attaches :result/error with message and data-keys"
    (let [err {:message "planner MCP artifact not found"
               :data {:phase :plan
                      :llm-content-length 4200}}
          pr  {:name :plan :result {:status :error :error err}}
          d   (exec/dag-skip-diagnostic pr :no-plan-id)
          re  (:result/error d)]
      (is (some? re) ":result/error should be present on :status :error")
      (is (= "planner MCP artifact not found" (:error/message re)))
      (is (= [:llm-content-length :phase] (:error/data-keys re))))))

(deftest diagnostic-surfaces-anomaly-keyword
  (testing "anomaly keyword on error is preserved (trimmed to known category)"
    (let [err {:message "something" :anomaly :anomalies.agent/invoke-failed}
          pr  {:name :plan :result {:status :error :error err}}
          d   (exec/dag-skip-diagnostic pr :no-plan-id)]
      (is (= :anomalies.agent/invoke-failed (:anomaly (:result/error d)))))))

(deftest diagnostic-truncates-long-error-message
  (testing "error message > 500 chars is truncated (don't bloat the event)"
    (let [long-msg (apply str (repeat 1000 "x"))
          err {:message long-msg}
          pr  {:name :plan :result {:status :error :error err}}
          d   (exec/dag-skip-diagnostic pr :no-plan-id)]
      (is (= 500 (count (:error/message (:result/error d))))))))

(deftest diagnostic-no-result-error-when-success
  (testing "when result status isn't error/failed/failure, no :result/error key"
    (let [pr {:name :plan :result {:status :success :output {:some/key 1}}}
          d  (exec/dag-skip-diagnostic pr :no-plan-id)]
      (is (nil? (:result/error d))))))

(deftest diagnostic-no-tasks-includes-task-count
  (testing ":no-tasks skip includes :plan/task-count"
    (let [plan {:plan/id (random-uuid) :plan/tasks []}
          pr   (phase-result-with-plan plan)
          d    (exec/dag-skip-diagnostic pr :no-tasks)]
      (is (= 0 (:plan/task-count d)))
      (is (true? (:output/has-plan-id? d))))))

(deftest diagnostic-bounded-no-leakage
  (testing "diagnostic doesn't leak full plan content — only keys"
    (let [large-plan {:plan/id (random-uuid)
                      :plan/tasks (vec (repeat 100 {:task/id (random-uuid)
                                                    :task/description "big string"}))}
          pr  (phase-result-with-plan large-plan)
          d   (exec/dag-skip-diagnostic pr :no-tasks)]
      (is (= 100 (:plan/task-count d)))
      ;; Values should not appear anywhere in the diagnostic except task-count.
      (is (not (re-find #"big string" (pr-str d)))
          "diagnostic leaked plan content"))))
