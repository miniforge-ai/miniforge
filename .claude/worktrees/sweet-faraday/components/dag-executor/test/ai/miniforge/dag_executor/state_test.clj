(ns ai.miniforge.dag-executor.state-test
  "Tests for DAG task state management and event emission wiring."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.state :as state]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.event-stream.interface :as es]))

;; ============================================================================
;; State transition tests
;; ============================================================================

(deftest transition-task-pure-test
  (testing "valid transition returns ok result"
    (let [task (state/create-task-state (random-uuid) #{})
          result (state/transition-task task :ready)]
      (is (result/ok? result))
      (is (= :ready (:task/status (:data result))))))

  (testing "invalid transition returns error"
    (let [task (state/create-task-state (random-uuid) #{})
          result (state/transition-task task :merged)]
      (is (result/err? result)))))

(deftest transition-task!-test
  (testing "atomically transitions task in run state"
    (let [task-id (random-uuid)
          task (state/create-task-state task-id #{})
          run (state/create-run-state (random-uuid) {task-id task})
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom task-id :ready nil)]
      (is (result/ok? result))
      (is (= :ready (get-in @run-atom [:run/tasks task-id :task/status])))))

  (testing "returns error for nonexistent task"
    (let [run (state/create-run-state (random-uuid) {})
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom (random-uuid) :ready nil)]
      (is (result/err? result)))))

;; ============================================================================
;; Event emission wiring tests
;; ============================================================================

(deftest transition-task!-emits-event-test
  (testing "transition-task! emits task/state-changed when event-stream is configured"
    (let [stream (es/create-event-stream {:sinks []})
          wf-id (random-uuid)
          dag-id (random-uuid)
          task-id (random-uuid)
          task (state/create-task-state task-id #{})
          run (-> (state/create-run-state dag-id {task-id task})
                  (assoc-in [:run/config :event-stream] stream)
                  (assoc-in [:run/config :workflow-id] wf-id))
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom task-id :ready nil)]
      (is (result/ok? result))
      (let [events (es/get-events stream)]
        (is (= 1 (count events)))
        (let [event (first events)]
          (is (= :task/state-changed (:event/type event)))
          (is (= dag-id (:dag/id event)))
          (is (= task-id (:task/id event)))
          (is (= :pending (:task/from-state event)))
          (is (= :ready (:task/to-state event))))))))

(deftest transition-task!-works-without-event-stream-test
  (testing "transition-task! works normally without event-stream in config"
    (let [task-id (random-uuid)
          task (state/create-task-state task-id #{})
          run (state/create-run-state (random-uuid) {task-id task})
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom task-id :ready nil)]
      (is (result/ok? result))
      (is (= :ready (get-in @run-atom [:run/tasks task-id :task/status]))))))

;; ============================================================================
;; Query tests
;; ============================================================================

(deftest ready-tasks-test
  (testing "finds tasks with all deps satisfied"
    (let [task-a-id (random-uuid)
          task-b-id (random-uuid)
          task-a (state/create-task-state task-a-id #{})
          task-b (state/create-task-state task-b-id #{task-a-id})
          run (state/create-run-state (random-uuid) {task-a-id task-a
                                                      task-b-id task-b})]
      ;; Only task-a is ready (no deps)
      (is (= #{task-a-id} (state/ready-tasks run)))
      ;; After marking task-a merged, task-b becomes ready
      (let [run' (-> run
                     (assoc-in [:run/tasks task-a-id :task/status] :merged)
                     (state/mark-task-merged task-a-id))]
        (is (contains? (state/ready-tasks run') task-b-id))))))

(deftest terminal?-test
  (testing "terminal statuses"
    (is (state/terminal? :merged))
    (is (state/terminal? :failed))
    (is (state/terminal? :skipped))
    (is (not (state/terminal? :pending)))
    (is (not (state/terminal? :implementing)))))
