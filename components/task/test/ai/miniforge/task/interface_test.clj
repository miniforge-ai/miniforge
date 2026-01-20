(ns ai.miniforge.task.interface-test
  (:require [clojure.test :as test :refer [deftest testing is use-fixtures]]
            [ai.miniforge.task.interface :as task]))

;------------------------------------------------------------------------------ Fixtures

(defn reset-store-fixture [f]
  (task/reset-store!)
  (f)
  (task/reset-store!))

(use-fixtures :each reset-store-fixture)

;------------------------------------------------------------------------------ Layer 0
;; Task CRUD tests

(deftest create-task-test
  (testing "creates task with required fields"
    (let [t (task/create-task {:task/type :implement})]
      (is (uuid? (:task/id t)))
      (is (= :implement (:task/type t)))
      (is (= :pending (:task/status t)))))

  (testing "task is retrievable after creation"
    (let [t (task/create-task {:task/type :plan})]
      (is (= t (task/get-task (:task/id t)))))))

(deftest update-task-test
  (testing "updates task fields"
    (let [t (task/create-task {:task/type :implement})
          updated (task/update-task (:task/id t)
                                    {:task/constraints {:budget {:tokens 5000}}})]
      (is (= {:budget {:tokens 5000}} (:task/constraints updated))))))

(deftest delete-task-test
  (testing "removes task from store"
    (let [t (task/create-task {:task/type :test})]
      (task/delete-task (:task/id t))
      (is (nil? (task/get-task (:task/id t)))))))

;------------------------------------------------------------------------------ Layer 1
;; State transitions tests

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

;------------------------------------------------------------------------------ Layer 2
;; Query tests

(deftest query-tests
  (testing "tasks-by-status returns correct tasks"
    (let [_t1 (task/create-task {:task/type :plan})
          t2 (task/create-task {:task/type :implement})]
      (task/start-task (:task/id t2) (random-uuid))
      (is (= 1 (count (task/tasks-by-status :pending))))
      (is (= 1 (count (task/tasks-by-status :running))))))

  (testing "tasks-by-type returns correct tasks"
    (task/create-task {:task/type :plan})
    (task/create-task {:task/type :implement})
    (task/create-task {:task/type :implement})
    (is (= 1 (count (task/tasks-by-type :plan))))
    (is (= 2 (count (task/tasks-by-type :implement)))))

  (testing "all-tasks returns all tasks"
    (task/create-task {:task/type :plan})
    (task/create-task {:task/type :implement})
    (is (= 2 (count (task/all-tasks))))))

;------------------------------------------------------------------------------ Layer 3
;; Decomposition tests

(deftest decomposition-test
  (testing "parent-child relationships"
    (let [parent (task/create-task {:task/type :plan})
          _ (task/decompose-task (:task/id parent)
                                 [{:task/type :design}
                                  {:task/type :implement}
                                  {:task/type :test}])
          children (task/get-children (:task/id parent))]
      (is (= 3 (count children)))
      ;; Each child should reference parent
      (doseq [child children]
        (is (= parent (task/get-parent (:task/id child)))))))

  (testing "get-root-task traverses hierarchy"
    (let [root (task/create-task {:task/type :plan})
          _ (task/decompose-task (:task/id root) [{:task/type :design}])
          child (first (task/get-children (:task/id root)))
          _ (task/decompose-task (:task/id child) [{:task/type :implement}])
          grandchild (first (task/get-children (:task/id child)))]
      (is (= root (task/get-root-task (:task/id grandchild)))))))

;------------------------------------------------------------------------------ Layer 4
;; Priority queue integration tests

(deftest queue-integration-test
  (testing "queue with priority ordering"
    (let [q (task/create-queue)
          t1 {:task/id (random-uuid) :task/type :plan :task/status :pending}
          t2 {:task/id (random-uuid) :task/type :implement :task/status :pending}]
      (task/enqueue q t1 {:workflow-priority 5})
      (task/enqueue q t2 {:workflow-priority 9})
      (is (= 2 (task/queue-size q)))
      ;; Higher priority task should come first
      (let [first-task (task/dequeue q)]
        (is (= (:task/id t2) (:task/id first-task))))))

  (testing "queue operations"
    (let [q (task/create-queue)]
      (is (task/queue-empty? q))
      (task/enqueue q {:task/id (random-uuid) :task/type :plan :task/status :pending})
      (is (not (task/queue-empty? q)))
      (task/clear-queue q)
      (is (task/queue-empty? q)))))

;------------------------------------------------------------------------------ Layer 5
;; Dependency graph integration tests

(deftest graph-integration-test
  (testing "dependency management"
    (let [g (task/create-graph)
          t1 (random-uuid)
          t2 (random-uuid)
          t3 (random-uuid)]
      ;; Register tasks
      (task/register-task g t1)
      (task/register-task g t2)
      (task/register-task g t3)
      ;; t2 depends on t1, t3 depends on t2
      (is (true? (task/add-dependency g t1 t2)))
      (is (true? (task/add-dependency g t2 t3)))
      ;; Check dependencies
      (is (= #{t1} (task/get-dependencies g t2)))
      (is (= #{t2} (task/get-dependents g t1)))
      ;; Ready tasks with no completions
      (is (= #{t1} (task/ready-tasks g #{})))
      ;; After completing t1
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
      ;; This would create a cycle: t3 -> t1 -> t2 -> t3
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
        ;; t1 should come before t2, t2 before t3
        (is (< (.indexOf sorted t1) (.indexOf sorted t2)))
        (is (< (.indexOf sorted t2) (.indexOf sorted t3)))))))

;------------------------------------------------------------------------------ Layer 6
;; Edge cases

(deftest edge-cases-test
  (testing "operations on empty store"
    (is (= [] (task/all-tasks)))
    (is (= [] (task/tasks-by-status :pending)))
    (is (nil? (task/get-task (random-uuid)))))

  (testing "operations on empty queue"
    (let [q (task/create-queue)]
      (is (nil? (task/dequeue q)))
      (is (nil? (task/peek-queue q)))
      (is (nil? (task/remove-from-queue q (random-uuid))))))

  (testing "operations on empty graph"
    (let [g (task/create-graph)]
      (is (= #{} (task/ready-tasks g #{})))
      (is (= #{} (task/blocked-tasks g #{})))
      (is (= #{} (task/get-dependencies g (random-uuid))))
      (is (= #{} (task/get-dependents g (random-uuid)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.task.interface-test)

  :leave-this-here)
