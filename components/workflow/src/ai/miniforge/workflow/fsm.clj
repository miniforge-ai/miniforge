;; Copyright 2025 miniforge.ai
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

   Defines explicit states, valid transitions, and guards for workflow execution.")

;; ============================================================================
;; FSM Definition
;; ============================================================================

(def workflow-states
  "Valid states for workflow execution."
  #{:pending     ; Initial state, workflow not started
    :running     ; Workflow is executing
    :completed   ; Workflow completed successfully
    :failed      ; Workflow failed
    :paused      ; Workflow execution paused
    :cancelled}) ; Workflow cancelled by user

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
;; FSM Operations
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

     ;; Valid transition
     :else
     {:success? true
      :state (next-state current-state event)})))

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
