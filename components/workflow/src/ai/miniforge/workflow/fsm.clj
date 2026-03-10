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

(ns ai.miniforge.workflow.fsm
  "Finite State Machine for workflow execution.

   Uses the fsm component (clj-statecharts wrapper) for state machine implementation.
   Defines explicit states, valid transitions, and guards for workflow execution.

   States:
   - :pending   - Initial state, workflow not started
   - :running   - Workflow is executing
   - :completed - Workflow completed successfully
   - :failed    - Workflow failed
   - :paused    - Workflow execution paused
   - :cancelled - Workflow cancelled by user

   Events:
   - :start   - Begin workflow execution
   - :complete - Mark workflow as completed
   - :fail    - Mark workflow as failed
   - :pause   - Pause execution
   - :resume  - Resume paused execution
   - :cancel  - Cancel workflow"
  (:require
   [ai.miniforge.fsm.interface :as fsm]))

;; ============================================================================
;; FSM Definition using clj-statecharts
;; ============================================================================

(def workflow-machine-config
  "Workflow execution state machine configuration."
  {:fsm/id :workflow-execution
   :fsm/initial :pending
   :fsm/context {}
   :fsm/states
   {:pending   {:on {:start :running}}
    :running   {:on {:complete :completed
                     :fail :failed
                     :pause :paused
                     :cancel :cancelled}}
    :paused    {:on {:resume :running
                     :cancel :cancelled}}
    :completed {:type :final}
    :failed    {:type :final}
    :cancelled {:type :final}}})

(def workflow-machine
  "Compiled workflow state machine."
  (fsm/define-machine workflow-machine-config))

;; ============================================================================
;; Legacy API - for backward compatibility with state.clj
;; ============================================================================

(def workflow-states
  "Valid states for workflow execution."
  #{:pending :running :completed :failed :paused :cancelled})

(def workflow-transitions
  "Valid state transitions. Map of [from-state event] -> to-state."
  {[:pending :start]      :running
   [:running :complete]   :completed
   [:running :fail]       :failed
   [:running :pause]      :paused
   [:running :cancel]     :cancelled
   [:paused :resume]      :running
   [:paused :cancel]      :cancelled})

(def terminal-states
  "States that are terminal (no further transitions)."
  #{:completed :failed :cancelled})

;; ============================================================================
;; FSM Operations (legacy API backed by clj-statecharts)
;; ============================================================================

(defn valid-transition?
  "Check if a transition from current state via event is valid.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition

   Returns: boolean"
  [current-state event]
  (contains? workflow-transitions [current-state event]))

(defn terminal-state?
  "Check if state is terminal (no further transitions allowed).

   Arguments:
   - state: State keyword

   Returns: boolean"
  [state]
  (contains? terminal-states state))

(defn succeeded?
  "Check if a transition result indicates success."
  [result]
  (boolean (:success? result)))

(defn next-state
  "Get next state for a transition.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition

   Returns: Next state keyword or nil if transition invalid"
  [current-state event]
  (get workflow-transitions [current-state event]))

(defn transition
  "Attempt state transition with guard checking.

   Uses clj-statecharts internally for proper FSM semantics.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition
   - guard-fn: Optional guard function (fn [current-state event] -> boolean)

   Returns:
   - {:success? true :state new-state} if transition valid
   - {:success? false :error reason} if transition invalid"
  ([current-state event]
   (transition current-state event (constantly true)))

  ([current-state event guard-fn]
   (cond
     ;; State already terminal
     (terminal-state? current-state)
     {:success? false
      :error :terminal-state
      :message (str "Cannot transition from terminal state: " current-state)}

     ;; Invalid state
     (not (contains? workflow-states current-state))
     {:success? false
      :error :invalid-state
      :message (str "Invalid current state: " current-state)}

     ;; Guard function failed
     (not (guard-fn current-state event))
     {:success? false
      :error :guard-failed
      :message "Guard function rejected transition"}

     ;; Invalid transition
     (not (valid-transition? current-state event))
     {:success? false
      :error :invalid-transition
      :message (str "No transition defined for [" current-state " " event "]")}

     ;; Valid transition - use clj-statecharts for actual transition
     :else
     (let [;; Create a temporary state with the current state value
           state-map {:_state current-state}
           new-state-map (fsm/transition workflow-machine state-map event)
           new-state (fsm/current-state new-state-map)]
       {:success? true
        :state new-state}))))

(defn valid-state?
  "Check if a state keyword is valid.

   Arguments:
   - state: State keyword

   Returns: boolean"
  [state]
  (contains? workflow-states state))

;; ============================================================================
;; FSM Visualization
;; ============================================================================

(defn get-available-events
  "Get available events from current state.

   Arguments:
   - current-state: Current FSM state keyword

   Returns: Vector of event keywords"
  [current-state]
  (vec (keep (fn [[[from-state event] _to-state]]
               (when (= from-state current-state)
                 event))
             workflow-transitions)))

(defn fsm-graph
  "Get FSM as a graph structure for visualization.

   Returns: Map with :states and :transitions"
  []
  {:states workflow-states
   :transitions (vec (map (fn [[[from event] to]]
                           {:from from :event event :to to})
                         workflow-transitions))
   :terminal-states terminal-states})

;; ============================================================================
;; New clj-statecharts API
;; ============================================================================

(defn get-machine
  "Get the compiled workflow state machine for direct clj-statecharts usage.

   Returns: Compiled state machine definition"
  []
  workflow-machine)

(defn initialize
  "Initialize workflow FSM state.

   Returns: Initial FSM state map with :_state = :pending"
  []
  (fsm/initialize workflow-machine))

(defn transition-fsm
  "Transition the workflow FSM using full statecharts semantics.

   Arguments:
   - state: Current FSM state map (from initialize or previous transition)
   - event: Event keyword or map {:type :event-type :data ...}

   Returns: New FSM state map"
  [state event]
  (fsm/transition workflow-machine state event))

(defn current-state
  "Get the current state keyword from FSM state map.

   Arguments:
   - state: FSM state map

   Returns: State keyword"
  [state]
  (fsm/current-state state))

(defn is-final?
  "Check if the FSM is in a final (terminal) state.

   Arguments:
   - state: FSM state map

   Returns: boolean"
  [state]
  (fsm/final? workflow-machine state))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Using the legacy API (for backward compatibility)
  (valid-transition? :pending :start)  ;; => true
  (valid-transition? :pending :fail)   ;; => false

  (transition :pending :start)
  ;; => {:success? true :state :running}

  (transition :completed :start)
  ;; => {:success? false :error :terminal-state ...}

  (get-available-events :running)
  ;; => [:complete :fail :pause :cancel]

  ;; Using the new clj-statecharts API
  (def s0 (initialize))
  (current-state s0)  ;; => :pending

  (def s1 (transition-fsm s0 :start))
  (current-state s1)  ;; => :running

  (def s2 (transition-fsm s1 :complete))
  (current-state s2)  ;; => :completed
  (is-final? s2)      ;; => true

  :leave-this-here)
