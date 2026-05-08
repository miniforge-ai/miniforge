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

(ns ai.miniforge.task.anomaly.lookup-task-test
  "Coverage for `core/lookup-task` (anomaly-returning) and the four
   boundary callers `update-task!`, `delete-task!`, `transition-task!`
   (via `start-task!`), and `decompose-task!` that escalate the
   :not-found anomaly to a slingshot `:anomalies/not-found` throw.

   The same pattern carries the parent-task-not-found message at
   the `decompose-task!` boundary."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.task.core :as core])
  (:import
   (clojure.lang ExceptionInfo)))

(defn- reset-store-fixture [f]
  (core/reset-store!)
  (f)
  (core/reset-store!))

(use-fixtures :each reset-store-fixture)

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)

(deftest lookup-task-returns-task-when-present
  (testing "lookup-task returns the stored task map on hit"
    (let [t (core/create-task! {:task/type :implement})
          result (core/lookup-task (:task/id t))]
      (is (not (anomaly/anomaly? result)))
      (is (= (:task/id t) (:task/id result)))
      (is (= :implement (:task/type result))))))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)

(deftest lookup-task-returns-not-found-anomaly
  (testing "missing task yields :not-found anomaly with task-id"
    (let [missing-id (random-uuid)
          result (core/lookup-task missing-id)]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (is (= missing-id (:task-id (:anomaly/data result))))
      (is (= core/task-not-found-message (:anomaly/message result))))))

(deftest lookup-task-honors-custom-message
  (testing "two-arg arity overrides the message — used by decompose for parent-not-found"
    (let [missing-id (random-uuid)
          result (core/lookup-task missing-id core/parent-task-not-found-message)]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (is (= core/parent-task-not-found-message (:anomaly/message result)))
      (is (= missing-id (:task-id (:anomaly/data result)))))))

(deftest lookup-task-rejection-returns-anomaly-not-nil
  (testing "anomaly is returned in place of nil — distinct from get-task semantics"
    (let [missing-id (random-uuid)]
      (is (nil? (core/get-task missing-id)))
      (is (anomaly/anomaly? (core/lookup-task missing-id))))))

;------------------------------------------------------------------------------ Boundary helpers escalate via slingshot

(deftest update-task-throws-not-found
  (testing "update-task! on missing task escalates to slingshot"
    (is (thrown? ExceptionInfo
                 (core/update-task! (random-uuid) {:task/status :running})))))

(deftest update-task-thrown-ex-data-preserves-anomaly-shape
  (testing "ex-data carries :anomalies/not-found for try+ catches"
    (let [missing-id (random-uuid)]
      (try
        (core/update-task! missing-id {:task/status :running})
        (is false "should have thrown")
        (catch ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :anomalies/not-found (:anomaly/category data)))
            (is (= missing-id (:task-id data)))))))))

(deftest delete-task-throws-not-found
  (testing "delete-task! on missing task escalates to slingshot"
    (is (thrown? ExceptionInfo
                 (core/delete-task! (random-uuid))))))

(deftest start-task-throws-not-found
  (testing "start-task! routes through transition-task! and escalates"
    (is (thrown? ExceptionInfo
                 (core/start-task! (random-uuid) (random-uuid))))))

(deftest decompose-task-throws-parent-not-found
  (testing "decompose-task! on missing parent escalates with parent-not-found message"
    (let [missing-parent-id (random-uuid)]
      (try
        (core/decompose-task! missing-parent-id [{:task/type :design}])
        (is false "should have thrown")
        (catch ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :anomalies/not-found (:anomaly/category data)))
            (is (= missing-parent-id (:task-id data)))
            (is (= core/parent-task-not-found-message (:anomaly/message data)))))))))

(deftest decompose-task-validates-parent-before-side-effects
  (testing "no orphaned children created when parent missing"
    (let [missing-parent-id (random-uuid)]
      (try
        (core/decompose-task! missing-parent-id [{:task/type :design}
                                                 {:task/type :implement}])
        (is false "should have thrown")
        (catch ExceptionInfo _
          ;; No tasks should have been created — the lookup must
          ;; reject before compute-child-tasks / create-task! runs.
          (is (= 0 (count (core/all-tasks)))))))))
