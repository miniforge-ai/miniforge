(ns ai.miniforge.workflow.fsm-test
  "Tests for workflow FSM."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.fsm :as fsm]))

(deftest workflow-states-test
  (testing "All workflow states are defined"
    (is (contains? fsm/workflow-states :pending))
    (is (contains? fsm/workflow-states :running))
    (is (contains? fsm/workflow-states :completed))
    (is (contains? fsm/workflow-states :failed))
    (is (contains? fsm/workflow-states :paused))
    (is (contains? fsm/workflow-states :cancelled))))

(deftest valid-transition-test
  (testing "Valid transitions from pending"
    (is (fsm/valid-transition? :pending :start)))

  (testing "Valid transitions from running"
    (is (fsm/valid-transition? :running :complete))
    (is (fsm/valid-transition? :running :fail))
    (is (fsm/valid-transition? :running :pause))
    (is (fsm/valid-transition? :running :cancel)))

  (testing "Valid transitions from paused"
    (is (fsm/valid-transition? :paused :resume))
    (is (fsm/valid-transition? :paused :cancel)))

  (testing "Invalid transitions"
    (is (not (fsm/valid-transition? :pending :complete)))
    (is (not (fsm/valid-transition? :pending :fail)))
    (is (not (fsm/valid-transition? :completed :start)))
    (is (not (fsm/valid-transition? :failed :resume)))))

(deftest terminal-state-test
  (testing "Terminal states"
    (is (fsm/terminal-state? :completed))
    (is (fsm/terminal-state? :failed))
    (is (fsm/terminal-state? :cancelled)))

  (testing "Non-terminal states"
    (is (not (fsm/terminal-state? :pending)))
    (is (not (fsm/terminal-state? :running)))
    (is (not (fsm/terminal-state? :paused)))))

(deftest next-state-test
  (testing "Next state for valid transitions"
    (is (= :running (fsm/next-state :pending :start)))
    (is (= :completed (fsm/next-state :running :complete)))
    (is (= :failed (fsm/next-state :running :fail)))
    (is (= :paused (fsm/next-state :running :pause)))
    (is (= :cancelled (fsm/next-state :running :cancel)))
    (is (= :running (fsm/next-state :paused :resume)))
    (is (= :cancelled (fsm/next-state :paused :cancel))))

  (testing "Next state for invalid transitions"
    (is (nil? (fsm/next-state :pending :complete)))
    (is (nil? (fsm/next-state :completed :start)))))

(deftest transition-test
  (testing "Successful transitions"
    (let [result (fsm/transition :pending :start)]
      (is (:success? result))
      (is (= :running (:state result)))))

  (testing "Failed transition - terminal state"
    (let [result (fsm/transition :completed :start)]
      (is (not (:success? result)))
      (is (= :terminal-state (:error result)))))

  (testing "Failed transition - invalid state"
    (let [result (fsm/transition :invalid-state :start)]
      (is (not (:success? result)))
      (is (= :invalid-state (:error result)))))

  (testing "Failed transition - invalid transition"
    (let [result (fsm/transition :pending :complete)]
      (is (not (:success? result)))
      (is (= :invalid-transition (:error result)))))

  (testing "Transition with guard function"
    (let [guard (fn [_state _event] false)
          result (fsm/transition :pending :start guard)]
      (is (not (:success? result)))
      (is (= :guard-failed (:error result))))))

(deftest valid-state-test
  (testing "Valid states"
    (is (fsm/valid-state? :pending))
    (is (fsm/valid-state? :running))
    (is (fsm/valid-state? :completed)))

  (testing "Invalid states"
    (is (not (fsm/valid-state? :invalid)))
    (is (not (fsm/valid-state? :unknown)))))

(deftest get-available-events-test
  (testing "Available events from pending"
    (let [events (fsm/get-available-events :pending)]
      (is (= [:start] events))))

  (testing "Available events from running"
    (let [events (set (fsm/get-available-events :running))]
      (is (= #{:complete :fail :pause :cancel} events))))

  (testing "Available events from paused"
    (let [events (set (fsm/get-available-events :paused))]
      (is (= #{:resume :cancel} events))))

  (testing "No available events from terminal states"
    (is (empty? (fsm/get-available-events :completed)))
    (is (empty? (fsm/get-available-events :failed)))
    (is (empty? (fsm/get-available-events :cancelled)))))

(deftest fsm-graph-test
  (testing "FSM graph structure"
    (let [graph (fsm/fsm-graph)]
      (is (set? (:states graph)))
      (is (vector? (:transitions graph)))
      (is (set? (:terminal-states graph)))
      (is (= fsm/workflow-states (:states graph)))
      (is (= fsm/terminal-states (:terminal-states graph))))))

(deftest fsm-workflow-lifecycle-test
  (testing "Complete workflow lifecycle"
    ;; Start: pending -> running
    (let [r1 (fsm/transition :pending :start)]
      (is (:success? r1))
      (is (= :running (:state r1)))

      ;; Complete: running -> completed
      (let [r2 (fsm/transition (:state r1) :complete)]
        (is (:success? r2))
        (is (= :completed (:state r2)))

        ;; Cannot transition from terminal state
        (let [r3 (fsm/transition (:state r2) :start)]
          (is (not (:success? r3)))
          (is (= :terminal-state (:error r3)))))))

  (testing "Failed workflow lifecycle"
    ;; Start: pending -> running
    (let [r1 (fsm/transition :pending :start)]
      (is (:success? r1))
      (is (= :running (:state r1)))

      ;; Fail: running -> failed
      (let [r2 (fsm/transition (:state r1) :fail)]
        (is (:success? r2))
        (is (= :failed (:state r2))))))

  (testing "Paused workflow lifecycle"
    ;; Start: pending -> running
    (let [r1 (fsm/transition :pending :start)]
      (is (:success? r1))
      (is (= :running (:state r1)))

      ;; Pause: running -> paused
      (let [r2 (fsm/transition (:state r1) :pause)]
        (is (:success? r2))
        (is (= :paused (:state r2)))

        ;; Resume: paused -> running
        (let [r3 (fsm/transition (:state r2) :resume)]
          (is (:success? r3))
          (is (= :running (:state r3)))

          ;; Complete: running -> completed
          (let [r4 (fsm/transition (:state r3) :complete)]
            (is (:success? r4))
            (is (= :completed (:state r4)))))))))
