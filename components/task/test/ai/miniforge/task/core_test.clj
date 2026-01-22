(ns ai.miniforge.task.core-test
  (:require [clojure.test :as test :refer [deftest testing is use-fixtures]]
            [ai.miniforge.task.core :as core]))

;------------------------------------------------------------------------------ Fixtures

(defn reset-store-fixture [f]
  (core/reset-store!)
  (f)
  (core/reset-store!))

(use-fixtures :each reset-store-fixture)

;------------------------------------------------------------------------------ Layer 0
;; State machine tests

(deftest valid-transition?-test
  (testing "valid transitions from :pending"
    (is (true? (core/valid-transition? :pending :running)))
    (is (true? (core/valid-transition? :pending :blocked))))

  (testing "valid transitions from :running"
    (is (true? (core/valid-transition? :running :completed)))
    (is (true? (core/valid-transition? :running :failed))))

  (testing "valid transitions from :blocked"
    (is (true? (core/valid-transition? :blocked :pending))))

  (testing "terminal states have no transitions"
    (is (false? (core/valid-transition? :completed :anything)))
    (is (false? (core/valid-transition? :failed :anything))))

  (testing "invalid transitions"
    (is (false? (core/valid-transition? :pending :completed)))
    (is (false? (core/valid-transition? :running :pending)))
    (is (false? (core/valid-transition? :blocked :running)))))

(deftest validate-transition-test
  (testing "valid transition returns to-state"
    (is (= :running (core/validate-transition :pending :running))))

  (testing "invalid transition throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid state transition"
                          (core/validate-transition :pending :completed)))))

(deftest make-task-test
  (testing "creates task with required fields"
    (let [task (core/make-task {:task/type :implement})]
      (is (uuid? (:task/id task)))
      (is (= :implement (:task/type task)))
      (is (= :pending (:task/status task)))))

  (testing "accepts provided id"
    (let [id (random-uuid)
          task (core/make-task {:task/id id :task/type :plan})]
      (is (= id (:task/id task)))))

  (testing "accepts custom status"
    (let [task (core/make-task {:task/type :test :task/status :blocked})]
      (is (= :blocked (:task/status task)))))

  (testing "includes constraints when provided"
    (let [task (core/make-task {:task/type :implement
                                :task/constraints {:budget {:tokens 50000}}})]
      (is (= {:budget {:tokens 50000}} (:task/constraints task))))))

;------------------------------------------------------------------------------ Layer 1
;; CRUD tests

(deftest create-task!-test
  (testing "creates and stores task"
    (let [task (core/create-task! {:task/type :implement})]
      (is (uuid? (:task/id task)))
      (is (= task (core/get-task (:task/id task))))))

  (testing "task appears in store"
    (let [task (core/create-task! {:task/type :plan})]
      (is (contains? (core/get-store) (:task/id task))))))

(deftest get-task-test
  (testing "returns nil for non-existent task"
    (is (nil? (core/get-task (random-uuid)))))

  (testing "returns task when exists"
    (let [task (core/create-task! {:task/type :test})]
      (is (= task (core/get-task (:task/id task)))))))

(deftest update-task!-test
  (testing "updates task fields"
    (let [task (core/create-task! {:task/type :implement})
          updated (core/update-task! (:task/id task)
                                     {:task/constraints {:budget {:tokens 10000}}})]
      (is (= {:budget {:tokens 10000}} (:task/constraints updated)))))

  (testing "throws for non-existent task"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Task not found"
                          (core/update-task! (random-uuid) {:task/status :running})))))

(deftest delete-task!-test
  (testing "deletes task from store"
    (let [task (core/create-task! {:task/type :review})
          deleted (core/delete-task! (:task/id task))]
      (is (= task deleted))
      (is (nil? (core/get-task (:task/id task))))))

  (testing "throws for non-existent task"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Task not found"
                          (core/delete-task! (random-uuid))))))

;------------------------------------------------------------------------------ Layer 2
;; State transition tests

(deftest start-task!-test
  (testing "transitions pending task to running"
    (let [task (core/create-task! {:task/type :implement})
          agent-id (random-uuid)
          started (core/start-task! (:task/id task) agent-id)]
      (is (= :running (:task/status started)))
      (is (= agent-id (:task/agent started)))))

  (testing "throws for non-pending task"
    (let [task (core/create-task! {:task/type :implement})
          agent-id (random-uuid)]
      (core/start-task! (:task/id task) agent-id)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid state transition"
                            (core/start-task! (:task/id task) agent-id))))))

(deftest complete-task!-test
  (testing "transitions running task to completed"
    (let [task (core/create-task! {:task/type :implement})
          _ (core/start-task! (:task/id task) (random-uuid))
          completed (core/complete-task! (:task/id task) {:signals [:done]})]
      (is (= :completed (:task/status completed)))
      (is (= :success (get-in completed [:task/result :outcome])))))

  (testing "throws for non-running task"
    (let [task (core/create-task! {:task/type :implement})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid state transition"
                            (core/complete-task! (:task/id task) {}))))))

(deftest fail-task!-test
  (testing "transitions running task to failed"
    (let [task (core/create-task! {:task/type :implement})
          _ (core/start-task! (:task/id task) (random-uuid))
          failed (core/fail-task! (:task/id task) "timeout")]
      (is (= :failed (:task/status failed)))
      (is (= :failure (get-in failed [:task/result :outcome])))
      (is (= "timeout" (get-in failed [:task/result :error])))))

  (testing "throws for non-running task"
    (let [task (core/create-task! {:task/type :implement})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid state transition"
                            (core/fail-task! (:task/id task) "error"))))))

(deftest block-task!-test
  (testing "transitions pending task to blocked"
    (let [task (core/create-task! {:task/type :implement})
          blocked (core/block-task! (:task/id task) "waiting for deps")]
      (is (= :blocked (:task/status blocked)))
      (is (= "waiting for deps" (:task/blocked-reason blocked)))))

  (testing "throws for non-pending task"
    (let [task (core/create-task! {:task/type :implement})
          _ (core/start-task! (:task/id task) (random-uuid))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid state transition"
                            (core/block-task! (:task/id task) "reason"))))))

(deftest unblock-task!-test
  (testing "transitions blocked task to pending"
    (let [task (core/create-task! {:task/type :implement})
          _ (core/block-task! (:task/id task) "waiting")
          unblocked (core/unblock-task! (:task/id task))]
      (is (= :pending (:task/status unblocked)))))

  (testing "throws for non-blocked task"
    (let [task (core/create-task! {:task/type :implement})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid state transition"
                            (core/unblock-task! (:task/id task)))))))

;------------------------------------------------------------------------------ Layer 2
;; Query tests

(deftest tasks-by-status-test
  (testing "returns empty vector when no tasks"
    (is (= [] (core/tasks-by-status :pending))))

  (testing "returns tasks with matching status"
    (let [t1 (core/create-task! {:task/type :plan})
          t2 (core/create-task! {:task/type :implement})
          _ (core/start-task! (:task/id t2) (random-uuid))
          pending (core/tasks-by-status :pending)
          running (core/tasks-by-status :running)]
      (is (= 1 (count pending)))
      (is (= (:task/id t1) (:task/id (first pending))))
      (is (= 1 (count running)))
      (is (= (:task/id t2) (:task/id (first running)))))))

(deftest tasks-by-agent-test
  (testing "returns empty vector when no matching tasks"
    (is (= [] (core/tasks-by-agent (random-uuid)))))

  (testing "returns tasks assigned to agent"
    (let [agent-1 (random-uuid)
          agent-2 (random-uuid)
          t1 (core/create-task! {:task/type :plan})
          t2 (core/create-task! {:task/type :implement})
          _ (core/start-task! (:task/id t1) agent-1)
          _ (core/start-task! (:task/id t2) agent-2)]
      (is (= 1 (count (core/tasks-by-agent agent-1))))
      (is (= (:task/id t1) (:task/id (first (core/tasks-by-agent agent-1))))))))

(deftest tasks-by-type-test
  (testing "returns tasks with matching type"
    (core/create-task! {:task/type :plan})
    (core/create-task! {:task/type :implement})
    (core/create-task! {:task/type :implement})
    (is (= 1 (count (core/tasks-by-type :plan))))
    (is (= 2 (count (core/tasks-by-type :implement))))))

;------------------------------------------------------------------------------ Layer 2
;; Decomposition tests

(deftest decompose-task!-test
  (testing "creates child tasks with parent reference"
    (let [parent (core/create-task! {:task/type :plan})
          updated (core/decompose-task! (:task/id parent)
                                        [{:task/type :design}
                                         {:task/type :implement}])
          children (:task/children updated)]
      (is (= 2 (count children)))
      (doseq [child-id children]
        (let [child (core/get-task child-id)]
          (is (= (:task/id parent) (:task/parent child)))))))

  (testing "throws for non-existent parent"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parent task not found"
                          (core/decompose-task! (random-uuid)
                                                [{:task/type :test}])))))

(deftest get-children-test
  (testing "returns empty vector for task without children"
    (let [task (core/create-task! {:task/type :plan})]
      (is (= [] (core/get-children (:task/id task))))))

  (testing "returns child tasks"
    (let [parent (core/create-task! {:task/type :plan})
          _ (core/decompose-task! (:task/id parent)
                                  [{:task/type :design}
                                   {:task/type :implement}])
          children (core/get-children (:task/id parent))]
      (is (= 2 (count children)))
      (is (every? #(contains? #{:design :implement} (:task/type %)) children)))))

(deftest get-parent-test
  (testing "returns nil for root task"
    (let [task (core/create-task! {:task/type :plan})]
      (is (nil? (core/get-parent (:task/id task))))))

  (testing "returns parent task"
    (let [parent (core/create-task! {:task/type :plan})
          parent-id (:task/id parent)
          _ (core/decompose-task! parent-id [{:task/type :design}])
          children (core/get-children parent-id)
          child-id (:task/id (first children))
          ;; Refetch parent after decompose (it now has :children)
          current-parent (core/get-task parent-id)]
      (is (= current-parent (core/get-parent child-id))))))

(deftest get-root-task-test
  (testing "returns task itself for root task"
    (let [task (core/create-task! {:task/type :plan})]
      (is (= task (core/get-root-task (:task/id task))))))

  (testing "returns root ancestor"
    (let [root (core/create-task! {:task/type :plan})
          root-id (:task/id root)
          _ (core/decompose-task! root-id [{:task/type :design}])
          child (first (core/get-children root-id))
          _ (core/decompose-task! (:task/id child) [{:task/type :implement}])
          grandchild (first (core/get-children (:task/id child)))
          ;; Refetch root after decompose (it now has :children)
          current-root (core/get-task root-id)]
      (is (= current-root (core/get-root-task (:task/id grandchild)))))))

(deftest all-children-completed?-test
  (testing "returns falsy for task with no children"
    (let [task (core/create-task! {:task/type :plan})]
      ;; Returns nil (falsy) when no children - different from false but logically same
      (is (not (core/all-children-completed? (:task/id task))))))

  (testing "returns false when not all children completed"
    (let [parent (core/create-task! {:task/type :plan})
          _ (core/decompose-task! (:task/id parent)
                                  [{:task/type :design}
                                   {:task/type :implement}])
          children (core/get-children (:task/id parent))
          child-1 (first children)]
      (core/start-task! (:task/id child-1) (random-uuid))
      (core/complete-task! (:task/id child-1) {})
      (is (false? (core/all-children-completed? (:task/id parent))))))

  (testing "returns true when all children completed"
    (let [parent (core/create-task! {:task/type :plan})
          _ (core/decompose-task! (:task/id parent)
                                  [{:task/type :design}
                                   {:task/type :implement}])
          children (core/get-children (:task/id parent))]
      (doseq [child children]
        (core/start-task! (:task/id child) (random-uuid))
        (core/complete-task! (:task/id child) {}))
      (is (true? (core/all-children-completed? (:task/id parent)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.task.core-test)

  :leave-this-here)
