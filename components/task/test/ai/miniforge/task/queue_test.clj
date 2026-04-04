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

(ns ai.miniforge.task.queue-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.task.queue :as queue]))

;------------------------------------------------------------------------------ Layer 0
;; Priority calculation tests

(deftest calculate-priority-test
  (testing "higher workflow priority yields higher score"
    (let [task {:task/id (random-uuid) :task/type :implement :task/status :pending}
          now (System/currentTimeMillis)]
      (is (< (queue/calculate-priority task now true 5)
             (queue/calculate-priority task now true 8)))))

  (testing "ready tasks have higher priority than non-ready"
    (let [task {:task/id (random-uuid) :task/type :implement :task/status :pending}
          now (System/currentTimeMillis)]
      (is (< (queue/calculate-priority task now false 5)
             (queue/calculate-priority task now true 5)))))

  (testing "older tasks have slightly higher priority"
    (let [old-time (- (System/currentTimeMillis) 60000)  ; 1 minute ago
          old-task {:task/id (random-uuid)
                    :task/type :implement
                    :task/status :pending
                    :task/created-at (java.util.Date. old-time)}
          new-task {:task/id (random-uuid)
                    :task/type :implement
                    :task/status :pending
                    :task/created-at (java.util.Date.)}
          now (System/currentTimeMillis)]
      (is (< (queue/calculate-priority new-task now true 5)
             (queue/calculate-priority old-task now true 5))))))

;------------------------------------------------------------------------------ Layer 1
;; Queue operations tests

(deftest create-queue-test
  (testing "creates empty queue"
    (let [q (queue/create-queue)]
      (is (queue/queue-empty? q))
      (is (= 0 (queue/queue-size q))))))

(deftest enqueue-test
  (testing "adds task to queue"
    (let [q (queue/create-queue)
          task {:task/id (random-uuid) :task/type :implement :task/status :pending}]
      (queue/enqueue q task)
      (is (= 1 (queue/queue-size q)))))

  (testing "multiple tasks can be enqueued"
    (let [q (queue/create-queue)]
      (queue/enqueue q {:task/id (random-uuid) :task/type :plan :task/status :pending})
      (queue/enqueue q {:task/id (random-uuid) :task/type :implement :task/status :pending})
      (queue/enqueue q {:task/id (random-uuid) :task/type :test :task/status :pending})
      (is (= 3 (queue/queue-size q))))))

(deftest dequeue-test
  (testing "returns nil for empty queue"
    (let [q (queue/create-queue)]
      (is (nil? (queue/dequeue q)))))

  (testing "returns highest priority task"
    (let [q (queue/create-queue)
          low-priority {:task/id (random-uuid) :task/type :implement :task/status :pending}
          high-priority {:task/id (random-uuid) :task/type :plan :task/status :pending}]
      (queue/enqueue q low-priority {:workflow-priority 3})
      (queue/enqueue q high-priority {:workflow-priority 9})
      (let [dequeued (queue/dequeue q)]
        (is (= (:task/id high-priority) (:task/id dequeued))))))

  (testing "removes task from queue"
    (let [q (queue/create-queue)
          task {:task/id (random-uuid) :task/type :implement :task/status :pending}]
      (queue/enqueue q task)
      (is (= 1 (queue/queue-size q)))
      (queue/dequeue q)
      (is (= 0 (queue/queue-size q)))))

  (testing "filters by role when specified"
    (let [q (queue/create-queue)
          plan-task {:task/id (random-uuid) :task/type :plan :task/status :pending}
          impl-task {:task/id (random-uuid) :task/type :implement :task/status :pending}]
      (queue/enqueue q plan-task {:workflow-priority 5})
      (queue/enqueue q impl-task {:workflow-priority 5})
      ;; Implementer should get implement task
      (let [dequeued (queue/dequeue q :implementer)]
        (is (= (:task/id impl-task) (:task/id dequeued))))
      ;; Planner should get plan task
      (let [dequeued (queue/dequeue q :planner)]
        (is (= (:task/id plan-task) (:task/id dequeued)))))))

(deftest peek-queue-test
  (testing "returns nil for empty queue"
    (let [q (queue/create-queue)]
      (is (nil? (queue/peek-queue q)))))

  (testing "returns highest priority task without removing"
    (let [q (queue/create-queue)
          task {:task/id (random-uuid) :task/type :implement :task/status :pending}]
      (queue/enqueue q task)
      (is (= (:task/id task) (:task/id (queue/peek-queue q))))
      (is (= 1 (queue/queue-size q)))))

  (testing "filters by role"
    (let [q (queue/create-queue)
          plan-task {:task/id (random-uuid) :task/type :plan :task/status :pending}
          impl-task {:task/id (random-uuid) :task/type :implement :task/status :pending}]
      (queue/enqueue q plan-task)
      (queue/enqueue q impl-task)
      (is (= (:task/id impl-task) (:task/id (queue/peek-queue q :implementer)))))))

(deftest queue-size-test
  (testing "returns correct size"
    (let [q (queue/create-queue)]
      (is (= 0 (queue/queue-size q)))
      (queue/enqueue q {:task/id (random-uuid) :task/type :plan :task/status :pending})
      (is (= 1 (queue/queue-size q)))
      (queue/enqueue q {:task/id (random-uuid) :task/type :implement :task/status :pending})
      (is (= 2 (queue/queue-size q))))))

(deftest queue-empty?-test
  (testing "returns true for empty queue"
    (let [q (queue/create-queue)]
      (is (true? (queue/queue-empty? q)))))

  (testing "returns false for non-empty queue"
    (let [q (queue/create-queue)]
      (queue/enqueue q {:task/id (random-uuid) :task/type :plan :task/status :pending})
      (is (false? (queue/queue-empty? q))))))

(deftest remove-from-queue-test
  (testing "removes specific task"
    (let [q (queue/create-queue)
          task-1 {:task/id (random-uuid) :task/type :plan :task/status :pending}
          task-2 {:task/id (random-uuid) :task/type :implement :task/status :pending}]
      (queue/enqueue q task-1)
      (queue/enqueue q task-2)
      (queue/remove-from-queue q (:task/id task-1))
      (is (= 1 (queue/queue-size q)))
      (is (= (:task/id task-2) (:task/id (queue/peek-queue q))))))

  (testing "returns nil for non-existent task"
    (let [q (queue/create-queue)]
      (is (nil? (queue/remove-from-queue q (random-uuid)))))))

(deftest update-priority-test
  (testing "updates task priority in queue"
    (let [q (queue/create-queue)
          low-task {:task/id (random-uuid) :task/type :implement :task/status :pending}
          high-task {:task/id (random-uuid) :task/type :plan :task/status :pending}]
      (queue/enqueue q low-task {:workflow-priority 3})
      (queue/enqueue q high-task {:workflow-priority 9})
      ;; High-task should be first
      (is (= (:task/id high-task) (:task/id (queue/peek-queue q))))
      ;; Boost low-task priority
      (queue/update-priority q (:task/id low-task) {:workflow-priority 10})
      ;; Now low-task should be first
      (is (= (:task/id low-task) (:task/id (queue/peek-queue q)))))))

(deftest tasks-in-queue-test
  (testing "returns all tasks sorted by priority"
    (let [q (queue/create-queue)
          t1 {:task/id (random-uuid) :task/type :plan :task/status :pending}
          t2 {:task/id (random-uuid) :task/type :implement :task/status :pending}
          t3 {:task/id (random-uuid) :task/type :test :task/status :pending}]
      (queue/enqueue q t1 {:workflow-priority 5})
      (queue/enqueue q t2 {:workflow-priority 8})
      (queue/enqueue q t3 {:workflow-priority 3})
      (let [tasks (queue/tasks-in-queue q)]
        (is (= 3 (count tasks)))
        ;; Should be sorted by priority (highest first)
        (is (= (:task/id t2) (:task/id (first tasks))))
        (is (= (:task/id t3) (:task/id (last tasks))))))))

(deftest clear-queue-test
  (testing "removes all tasks"
    (let [q (queue/create-queue)]
      (queue/enqueue q {:task/id (random-uuid) :task/type :plan :task/status :pending})
      (queue/enqueue q {:task/id (random-uuid) :task/type :implement :task/status :pending})
      (queue/clear-queue q)
      (is (queue/queue-empty? q)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.task.queue-test)

  :leave-this-here)
