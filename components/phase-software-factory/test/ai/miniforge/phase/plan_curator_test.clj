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

(ns ai.miniforge.phase.plan-curator-test
  "Tests for the plan phase curator interceptor (runs in the :exit slot
   after leave-plan). Validates that malformed plan artifacts are rejected
   at the phase boundary so they don't flow into :implement."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase.plan :as plan]))

(defn- plan-ctx
  "Build a minimal ctx with the shape curate-plan expects to inspect."
  [phase-result]
  {:phase (merge {:status :completed} phase-result)})

(defn- valid-plan-output []
  {:plan/id (random-uuid)
   :plan/name "sample"
   :plan/tasks [{:task/id (random-uuid)
                 :task/description "t1"
                 :task/type :implement}]})

(deftest accepts-well-formed-plan
  (testing "output has :plan/id and non-empty :plan/tasks"
    (let [ctx (plan-ctx {:result {:status :success :output (valid-plan-output)}})
          out (plan/curate-plan ctx)]
      (is (= :completed (get-in out [:phase :status])))
      (is (nil? (get-in out [:phase :error]))))))

(deftest accepts-already-satisfied
  (testing "already-satisfied passes through unchanged regardless of tasks"
    (let [ctx (plan-ctx {:status :already-satisfied
                         :result {:status :already-satisfied
                                  :output {:plan/id (random-uuid)
                                           :plan/tasks []
                                           :plan/summary "already done"}}})
          out (plan/curate-plan ctx)]
      (is (= :already-satisfied (get-in out [:phase :status]))
          "the curator MUST NOT override :already-satisfied"))))

(deftest rejects-error-status
  (testing "inner :status :error rejects with :curator/plan-status-not-success"
    (let [ctx (plan-ctx {:result {:status :error
                                  :error {:message "planner exploded"}}})
          out (plan/curate-plan ctx)]
      (is (= :failed (get-in out [:phase :status])))
      (is (= :curator/plan-status-not-success (get-in out [:phase :error :code])))
      (is (= :plan (get-in out [:phase :error :curator]))))))

(deftest rejects-failure-status
  (testing "inner :status :failure also rejects"
    (let [ctx (plan-ctx {:result {:status :failure}})
          out (plan/curate-plan ctx)]
      (is (= :failed (get-in out [:phase :status])))
      (is (= :curator/plan-status-not-success (get-in out [:phase :error :code]))))))

(deftest rejects-missing-plan-id
  (testing "output present but no :plan/id → :curator/plan-missing-id"
    (let [ctx (plan-ctx {:result {:status :success
                                  :output {:some-other-key "narration"}}})
          out (plan/curate-plan ctx)]
      (is (= :failed (get-in out [:phase :status])))
      (is (= :curator/plan-missing-id (get-in out [:phase :error :code]))))))

(deftest rejects-empty-tasks
  (testing ":plan/id present but :plan/tasks empty → :curator/plan-no-tasks"
    (let [plan-id (random-uuid)
          ctx (plan-ctx {:result {:status :success
                                  :output {:plan/id plan-id
                                           :plan/tasks []}}})
          out (plan/curate-plan ctx)]
      (is (= :failed (get-in out [:phase :status])))
      (is (= :curator/plan-no-tasks (get-in out [:phase :error :code]))))))

(deftest preserves-llm-content-preview-when-available
  (testing "preview text (when planner's anomaly recorded it) is carried through the rejection for post-mortems"
    (let [preview "Let me check a few more files..."
          ctx (plan-ctx {:result {:status :error
                                  :error {:message "throw+"
                                          :data {:llm-content-preview preview}}}})
          out (plan/curate-plan ctx)]
      (is (= preview (get-in out [:phase :error :llm-content-preview]))))))

(deftest rejection-carries-inner-result-status
  (testing "the phase error includes :inner-result-status so diagnostics can branch on it"
    (let [ctx (plan-ctx {:result {:status :error}})
          out (plan/curate-plan ctx)]
      (is (= :error (get-in out [:phase :error :inner-result-status]))))))
