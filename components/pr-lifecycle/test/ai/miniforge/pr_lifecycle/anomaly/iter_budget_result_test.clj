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

(ns ai.miniforge.pr-lifecycle.anomaly.iter-budget-result-test
  "Coverage for `controller/iter-budget-result` (anomaly-returning) and
   fix-loop boundary behavior in `handle-ci-failure!` /
   `handle-review-feedback!`.

   Iter-budget exhaustion returns a `:conflict` anomaly. The boundary
   helpers return the anomaly after recording failure state and history."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.pr-lifecycle.controller :as controller]))

(def ^:private test-task
  {:task/id (random-uuid)
   :task/type :implement
   :task/description "test task"})

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)

(deftest iter-budget-result-returns-ok-when-under-budget
  (testing "current < max returns :ok"
    (is (= :ok (controller/iter-budget-result "task-1" 0 3)))
    (is (= :ok (controller/iter-budget-result "task-1" 1 3)))
    (is (= :ok (controller/iter-budget-result "task-1" 2 3)))))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)

(deftest iter-budget-result-returns-anomaly-when-at-budget
  (testing "current = max returns :conflict anomaly"
    (let [result (controller/iter-budget-result "task-1" 3 3)]
      (is (anomaly/anomaly? result))
      (is (= :conflict (:anomaly/type result)))
      (is (= "task-1" (:task-id (:anomaly/data result))))
      (is (= 3 (:iterations (:anomaly/data result)))))))

(deftest iter-budget-result-returns-anomaly-when-over-budget
  (testing "current > max returns :conflict anomaly"
    (let [result (controller/iter-budget-result "task-1" 5 3)]
      (is (anomaly/anomaly? result))
      (is (= :conflict (:anomaly/type result)))
      (is (= 5 (:iterations (:anomaly/data result)))))))

(deftest iter-budget-result-zero-budget-rejects-immediately
  (testing "max=0 rejects on first attempt (current=0)"
    (let [result (controller/iter-budget-result "task-1" 0 0)]
      (is (anomaly/anomaly? result))
      (is (= :conflict (:anomaly/type result))))))

;------------------------------------------------------------------------------ Boundary helper returns anomaly after side effects

(deftest handle-ci-failure-returns-budget-anomaly
  (testing "handle-ci-failure! returns :conflict anomaly"
    (let [ctrl (controller/create-controller
                "dag" "run" "task" test-task
                :worktree-path "/tmp"
                :max-fix-iterations 2)]
      (swap! ctrl assoc :fix-iterations 2)
      (let [result (controller/handle-ci-failure! ctrl "logs")]
        (is (anomaly/anomaly? result))
        (is (= :conflict (:anomaly/type result)))
        (is (= "Max fix iterations exceeded" (:anomaly/message result)))))))

(deftest handle-review-feedback-returns-budget-anomaly
  (testing "handle-review-feedback! returns :conflict anomaly"
    (let [ctrl (controller/create-controller
                "dag" "run" "task" test-task
                :worktree-path "/tmp"
                :max-fix-iterations 1)]
      (swap! ctrl assoc :fix-iterations 1)
      (let [result (controller/handle-review-feedback! ctrl [{:body "fix"}])]
        (is (anomaly/anomaly? result))
        (is (= :conflict (:anomaly/type result)))
        (is (= "Max fix iterations exceeded" (:anomaly/message result)))))))

(deftest boundary-sets-failed-status-on-budget-exhaustion
  (testing "boundary helper transitions controller to :failed before throwing"
    (let [ctrl (controller/create-controller
                "dag" "run" "task" test-task
                :worktree-path "/tmp"
                :max-fix-iterations 3)]
      (swap! ctrl assoc :fix-iterations 3)
      (controller/handle-ci-failure! ctrl "logs")
      (is (= :failed (:status @ctrl))))))

(deftest boundary-records-history-on-budget-exhaustion
  (testing "boundary helper records :max-fix-iterations-exceeded in history"
    (let [ctrl (controller/create-controller
                "dag" "run" "task" test-task
                :worktree-path "/tmp"
                :max-fix-iterations 2)]
      (swap! ctrl assoc :fix-iterations 2)
      (controller/handle-ci-failure! ctrl "logs")
      (is (some #(= :max-fix-iterations-exceeded (:type %))
                (:history @ctrl))))))
