(ns ai.miniforge.task.interface-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.task.interface :as task]))

(deftest state-machine-integration-test
  (testing "full lifecycle: pending -> running -> completed"
    (let [t (task/create-task {:task/type :implement})
          agent-id (random-uuid)
          started (task/start-task (:task/id t) agent-id)
          completed (task/complete-task (:task/id t) {:signals [:done]})]
      (is (= :running (:task/status started)))
      (is (= :completed (:task/status completed)))
      (is (= :success (get-in completed [:task/result :outcome])))))

  (testing "full lifecycle: pending -> running -> failed"
    (let [t (task/create-task {:task/type :implement})
          agent-id (random-uuid)]
      (task/start-task (:task/id t) agent-id)
      (let [failed (task/fail-task (:task/id t) "timeout")]
        (is (= :failed (:task/status failed)))
        (is (= :failure (get-in failed [:task/result :outcome]))))))

  (testing "block and unblock cycle"
    (let [t (task/create-task {:task/type :implement})
          blocked (task/block-task (:task/id t) "waiting for deps")
          unblocked (task/unblock-task (:task/id t))]
      (is (= :blocked (:task/status blocked)))
      (is (= :pending (:task/status unblocked))))))

(deftest queue-integration-test
  (testing "queue with priority ordering"
    (let [q (task/create-queue)
          t1 {:task/id (random-uuid) :task/type :plan :task/status :pending}
          t2 {:task/id (random-uuid) :task/type :implement :task/status :pending}]
      (task/enqueue q t1 {:workflow-priority 5})
      (task/enqueue q t2 {:workflow-priority 9})
      (is (= 2 (task/queue-size q)))
      (let [first-task (task/dequeue q)]
        (is (= (:task/id t2) (:task/id first-task))))))

  (testing "queue operations"
    (let [q (task/create-queue)]
      (is (task/queue-empty? q))
      (task/enqueue q {:task/id (random-uuid) :task/type :plan :task/status :pending})
      (is (not (task/queue-empty? q)))
      (task/clear-queue q)
      (is (task/queue-empty? q)))))

(deftest graph-integration-test
  (testing "dependency management"
    (let [g (task/create-graph)
          t1 (random-uuid)
          t2 (random-uuid)
          t3 (random-uuid)]
      (task/register-task g t1)
      (task/register-task g t2)
      (task/register-task g t3)
      (is (true? (task/add-dependency g t1 t2)))
      (is (true? (task/add-dependency g t2 t3)))
      (is (= #{t1} (task/get-dependencies g t2)))
      (is (= #{t2} (task/get-dependents g t1)))
      (is (= #{t1} (task/ready-tasks g #{})))
      (is (= #{t2} (task/ready-tasks g #{t1})))))

  (testing "cycle detection"
    (let [g (task/create-graph)
          t1 (random-uuid)
          t2 (random-uuid)
          t3 (random-uuid)]
      (task/register-task g t1)
      (task/register-task g t2)
      (task/register-task g t3)
      (task/add-dependency g t1 t2)
      (task/add-dependency g t2 t3)
      (is (false? (task/add-dependency g t3 t1)))))

  (testing "topological sort"
    (let [g (task/create-graph)
          t1 (random-uuid)
          t2 (random-uuid)
          t3 (random-uuid)]
      (task/register-task g t1)
      (task/register-task g t2)
      (task/register-task g t3)
      (task/add-dependency g t1 t2)
      (task/add-dependency g t2 t3)
      (let [sorted (task/topological-sort g)]
        (is (some? sorted))
        (is (< (.indexOf sorted t1) (.indexOf sorted t2)))
        (is (< (.indexOf sorted t2) (.indexOf sorted t3)))))))
