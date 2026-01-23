(ns ai.miniforge.fsm.interface-test
  "Tests for the FSM component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.fsm.interface :as fsm]))

;; ============================================================================
;; Machine definition tests
;; ============================================================================

(deftest define-machine-basic-test
  (testing "define-machine creates a machine from config"
    (let [machine (fsm/define-machine
                   {:fsm/id :test
                    :fsm/initial :idle
                    :fsm/states
                    {:idle {:on {:start :running}}
                     :running {:on {:stop :idle}}}})]
      (is (some? machine))
      (is (= :test (:id machine))))))

(deftest define-machine-with-context-test
  (testing "define-machine includes initial context"
    (let [machine (fsm/define-machine
                   {:fsm/id :test
                    :fsm/initial :idle
                    :fsm/context {:counter 0}
                    :fsm/states
                    {:idle {:on {:start :running}}
                     :running {:type :final}}})]
      (is (= {:counter 0} (:context machine))))))

;; ============================================================================
;; Initialize tests
;; ============================================================================

(deftest initialize-test
  (testing "initialize returns initial state"
    (let [machine (fsm/define-machine
                   {:fsm/id :test
                    :fsm/initial :idle
                    :fsm/context {:x 1}
                    :fsm/states {:idle {:on {:go :done}}
                                 :done {:type :final}}})
          state (fsm/initialize machine)]
      (is (= :idle (fsm/current-state state)))
      (is (= 1 (:x (fsm/context state)))))))

;; ============================================================================
;; Transition tests
;; ============================================================================

(deftest transition-basic-test
  (testing "transition moves to target state"
    (let [machine (fsm/define-machine
                   {:fsm/id :test
                    :fsm/initial :a
                    :fsm/states {:a {:on {:next :b}}
                                 :b {:on {:next :c}}
                                 :c {:type :final}}})
          s0 (fsm/initialize machine)
          s1 (fsm/transition machine s0 :next)
          s2 (fsm/transition machine s1 :next)]
      (is (= :a (fsm/current-state s0)))
      (is (= :b (fsm/current-state s1)))
      (is (= :c (fsm/current-state s2))))))

(deftest transition-no-match-test
  (testing "transition stays in state when no matching event"
    (let [machine (fsm/define-machine
                   {:fsm/id :test
                    :fsm/initial :idle
                    :fsm/states {:idle {:on {:start :running}}
                                 :running {:type :final}}})
          s0 (fsm/initialize machine)
          s1 (fsm/transition machine s0 :unknown-event)]
      (is (= :idle (fsm/current-state s1))))))

(deftest transition-with-event-data-test
  (testing "transition accepts event with data"
    (let [machine (fsm/define-machine
                   {:fsm/id :test
                    :fsm/initial :idle
                    :fsm/states {:idle {:on {:go :done}}
                                 :done {:type :final}}})
          s0 (fsm/initialize machine)
          s1 (fsm/transition machine s0 {:type :go :data {:reason "test"}})]
      (is (= :done (fsm/current-state s1))))))

;; ============================================================================
;; State query tests
;; ============================================================================

(deftest in-state?-test
  (testing "in-state? checks current state"
    (let [machine (fsm/define-machine
                   {:fsm/id :test
                    :fsm/initial :idle
                    :fsm/states {:idle {:on {:start :running}}
                                 :running {:type :final}}})
          state (fsm/initialize machine)]
      (is (fsm/in-state? state :idle))
      (is (not (fsm/in-state? state :running))))))

(deftest final?-test
  (testing "final? detects final states"
    (let [machine (fsm/define-machine
                   {:fsm/id :test
                    :fsm/initial :working
                    :fsm/states {:working {:on {:done :finished}}
                                 :finished {:type :final}}})
          s0 (fsm/initialize machine)
          s1 (fsm/transition machine s0 :done)]
      (is (not (fsm/final? machine s0)))
      (is (fsm/final? machine s1)))))

;; ============================================================================
;; Context tests
;; ============================================================================

(deftest context-test
  (testing "context returns context without internal state"
    (let [machine (fsm/define-machine
                   {:fsm/id :test
                    :fsm/initial :idle
                    :fsm/context {:a 1 :b 2}
                    :fsm/states {:idle {:type :final}}})
          state (fsm/initialize machine)
          ctx (fsm/context state)]
      (is (= 1 (:a ctx)))
      (is (= 2 (:b ctx)))
      (is (not (contains? ctx :_state))))))

(deftest update-context-test
  (testing "update-context applies function to context"
    (let [machine (fsm/define-machine
                   {:fsm/id :test
                    :fsm/initial :idle
                    :fsm/context {:count 0}
                    :fsm/states {:idle {:type :final}}})
          s0 (fsm/initialize machine)
          s1 (fsm/update-context s0 update :count inc)]
      (is (= 0 (:count (fsm/context s0))))
      (is (= 1 (:count (fsm/context s1)))))))

;; ============================================================================
;; Guard tests
;; ============================================================================

(deftest guard-test
  (testing "guard creates predicate function"
    (let [g (fsm/guard (fn [ctx _event] (< (:attempts ctx) 3)))]
      (is (g {:attempts 0 :_state :x} {}))
      (is (g {:attempts 2 :_state :x} {}))
      (is (not (g {:attempts 3 :_state :x} {}))))))

(deftest all-guards-test
  (testing "all-guards combines with AND"
    (let [g1 (fsm/guard (fn [ctx _] (:a ctx)))
          g2 (fsm/guard (fn [ctx _] (:b ctx)))
          combined (fsm/all-guards g1 g2)]
      (is (combined {:a true :b true :_state :x} {}))
      (is (not (combined {:a true :b false :_state :x} {})))
      (is (not (combined {:a false :b true :_state :x} {}))))))

(deftest any-guard-test
  (testing "any-guard combines with OR"
    (let [g1 (fsm/guard (fn [ctx _] (:a ctx)))
          g2 (fsm/guard (fn [ctx _] (:b ctx)))
          combined (fsm/any-guard g1 g2)]
      (is (combined {:a true :b true :_state :x} {}))
      (is (combined {:a true :b false :_state :x} {}))
      (is (combined {:a false :b true :_state :x} {}))
      (is (not (combined {:a false :b false :_state :x} {}))))))

;; ============================================================================
;; Workflow FSM example test
;; ============================================================================

(deftest workflow-fsm-test
  (testing "workflow FSM handles typical workflow transitions"
    (let [machine (fsm/define-machine
                   {:fsm/id :workflow
                    :fsm/initial :idle
                    :fsm/context {:phase-index 0
                                  :phases [:plan :implement :verify]}
                    :fsm/states
                    {:idle      {:on {:start :running}}
                     :running   {:on {:phase-complete :running
                                      :workflow-complete :completed
                                      :fail :failed}}
                     :completed {:type :final}
                     :failed    {:type :final}}})
          s0 (fsm/initialize machine)
          s1 (fsm/transition machine s0 :start)
          s2 (fsm/transition machine s1 :phase-complete)
          s3 (fsm/transition machine s2 :workflow-complete)]
      (is (= :idle (fsm/current-state s0)))
      (is (= :running (fsm/current-state s1)))
      (is (= :running (fsm/current-state s2)))
      (is (= :completed (fsm/current-state s3)))
      (is (fsm/final? machine s3)))))
