;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-legacy
  "Compatibility API for the coarse workflow lifecycle."
  (:require [ai.miniforge.fsm.interface :as fsm]
            [ai.miniforge.workflow.state-legacy-model :as model]
            [ai.miniforge.workflow.state-legacy-result :as result]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} workflow-machine model/workflow-machine)

(def ^{:stratum 0} workflow-machine-config model/workflow-machine-config)

(def ^{:stratum 0} workflow-states model/workflow-states)

(def ^{:stratum 0} workflow-transitions model/workflow-transitions)

(def ^{:stratum 0} terminal-states model/terminal-states)

(defn ^{:stratum 0} succeeded?
  "Check if a transition result indicates success."
  [result]
  (boolean (:success? result)))

(defn- ^{:stratum 0} available-event [state [[from event] _]]
  (when (= from state) event))

(defn- ^{:stratum 0} graph-edge [[[from event] to]]
  {:from from :event event :to to})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} valid-transition?
  "Check if a transition from current state via event is valid.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition

   Returns: boolean"
  [current-state event]
  (contains? workflow-transitions [current-state event]))

(defn ^{:stratum 1} terminal-state?
  "Check if state is terminal (no further transitions allowed).

   Arguments:
   - state: State keyword

   Returns: boolean"
  [state]
  (contains? terminal-states state))

(defn ^{:stratum 1} next-state
  "Get next state for a transition.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition

   Returns: Next state keyword or nil if transition invalid"
  [current-state event]
  (get workflow-transitions [current-state event]))

(defn ^{:stratum 1} valid-state?
  "Check if a state keyword is valid.

   Arguments:
   - state: State keyword

   Returns: boolean"
  [state]
  (contains? workflow-states state))

(defn ^{:stratum 1} get-available-events [state]
  (into [] (keep (partial available-event state)) workflow-transitions))

(defn ^{:stratum 1} fsm-graph []
  (let [transitions (mapv graph-edge workflow-transitions)]
    {:states workflow-states :transitions transitions :terminal-states terminal-states}))

(defn ^{:stratum 1} get-machine
  "Get the compiled workflow state machine for direct clj-statecharts usage.

   Returns: Compiled state machine definition"
  []
  workflow-machine)

(defn ^{:stratum 1} initialize
  "Initialize workflow FSM state.

   Returns: Initial FSM state map with :_state = :pending"
  []
  (fsm/initialize workflow-machine))

(defn ^{:stratum 1} transition-fsm
  "Transition the workflow FSM using full statecharts semantics.

   Arguments:
   - state: Current FSM state map (from initialize or previous transition)
   - event: Event keyword or map {:type :event-type :data ...}

   Returns: New FSM state map"
  [state event]
  (fsm/transition workflow-machine state event))

(defn ^{:stratum 1} is-final?
  "Check if the FSM is in a final (terminal) state.

   Arguments:
   - state: FSM state map

   Returns: boolean"
  [state]
  (fsm/final? workflow-machine state))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} transition
  "Retain legacy result shapes and guard evaluation order."
  ([state event] (transition state event (constantly true)))
  ([state event guard-fn]
   (cond
     (terminal-state? state) (result/terminal state)
     (not (valid-state? state)) (result/invalid-state state)
     (not (guard-fn state event)) (result/rejected)
     (not (valid-transition? state event)) (result/invalid-transition state event)
     :else (result/accepted workflow-machine state event))))

(comment ::coarse-lifecycle-compatibility)
