;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-topology-test
  (:require [ai.miniforge.workflow.state-topology :as topology]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} states [pipeline]
  (:states (topology/build-raw-states {:workflow/id :topology :workflow/pipeline pipeline})))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} empty-workflow-retains-running-and-paused-lifecycle
  (let [table (states [])]
    (is (= :running (get-in table [:pending :on :start])))
    (is (= :completed (get-in table [:running :on :complete])))
    (is (= :running (get-in table [:paused :on :resume])))))

(deftest ^{:stratum 1} explicit-success-and-terminal-fallback-preserve-state-identities
  (let [table (states [{:phase :plan :on-success :done} {:phase :implement} {:phase :done}])]
    (is (= :phase-0-plan (get-in table [:pending :on :start])))
    (is (= :phase-2-done (get-in table [:phase-0-plan :on :phase/succeed])))
    (is (= :phase-1-implement (get-in table [:paused-phase-1-implement :on :resume])))
    (is (= :completed (get-in table [:phase-2-done :on :phase/succeed])))
    (is (not (contains? table :running)))))

(deftest ^{:stratum 1} guarded-redirect-keeps-infrastructure-retry-before-terminal-and-budget
  (let [table (states [{:phase :implement} {:phase :review :on-fail :implement}])
        branches (get-in table [:phase-1-review :on :phase/fail])]
    (is (= [:verdict/infra-retriable? :verdict/terminal? :budget/redirects-spent? nil nil]
           (mapv :guard branches)))
    (is (= [:phase-1-review :failed :failed :phase-0-implement :failed] (mapv :target branches)))
    (is (= [[:infra/inc-count] nil nil [:redirect/inc-count] nil] (mapv :actions branches)))))

(deftest ^{:stratum 1} unguarded-failure-and-terminal-phase-keep-declared-targets
  (let [table (states [{:phase :plan :terminal? true :on-fail :done} {:phase :done}])]
    (is (= :completed (get-in table [:phase-0-plan :on :phase/succeed])))
    (is (= :phase-1-done (get-in table [:phase-0-plan :on :phase/fail])))
    (is (= :phase-1-done (get-in table [:phase-0-plan :on :phase/already-done])))))
