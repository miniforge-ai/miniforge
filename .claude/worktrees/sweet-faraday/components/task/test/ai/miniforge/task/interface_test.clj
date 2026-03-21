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
    ;; Note: Previous testing block created _t1=:plan, t2=:implement
    ;; Create more tasks for this test
    (task/create-task {:task/type :plan})
    (task/create-task {:task/type :implement})
    (task/create-task {:task/type :implement})
    ;; Total: 2 plan (1 from before + 1 now), 3 implement (1 from before + 2 now)
    (is (= 2 (count (task/tasks-by-type :plan))))
    (is (= 3 (count (task/tasks-by-type :implement)))))

  (testing "all-tasks returns all tasks"
    ;; Creates 2 more tasks on top of existing ones
    (task/create-task {:task/type :plan})
    (task/create-task {:task/type :implement})
    ;; Total: 7 tasks (2 + 3 + 2)
    (is (= 7 (count (task/all-tasks))))))

;------------------------------------------------------------------------------ Layer 3
;; Decomposition tests

(deftest decomposition-test
  (testing "parent-child relationships"
    (let [parent (task/create-task {:task/type :plan})
          parent-id (:task/id parent)
          _ (task/decompose-task parent-id
                                 [{:task/type :design}
                                  {:task/type :implement}
                                  {:task/type :test}])
          children (task/get-children parent-id)
          ;; Refetch parent after decompose (it now has :children)
          current-parent (task/get-task parent-id)]
      (is (= 3 (count children)))
      ;; Each child should reference the current parent state
      (doseq [child children]
        (is (= current-parent (task/get-parent (:task/id child)))))))

  (testing "get-root-task traverses hierarchy"
    (let [root (task/create-task {:task/type :plan})
          root-id (:task/id root)
          _ (task/decompose-task root-id [{:task/type :design}])
          child (first (task/get-children root-id))
          _ (task/decompose-task (:task/id child) [{:task/type :implement}])
          grandchild (first (task/get-children (:task/id child)))
          ;; Refetch root after decompose (it now has :children)
          current-root (task/get-task root-id)]
      (is (= current-root (task/get-root-task (:task/id grandchild)))))))

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
