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

(ns ai.miniforge.workflow.supervision
  "Per-run supervision machine for live governance and operator posture.

   This machine is intentionally separate from the execution machine in
   `workflow.fsm`. It models the runtime supervisory stance around a run,
   not the workflow graph itself."
  (:require
   [ai.miniforge.fsm.interface :as fsm]))

;------------------------------------------------------------------------------ Layer 0
;; Machine definition

(def supervision-states
  "Known per-run supervision states per
   I-WORKFLOW-SUPERVISION-MACHINE-ARCHITECTURE §3.2."
  #{:nominal :warning :paused-by-supervisor :awaiting-operator :halted})

(def terminal-states
  "Supervision terminal states."
  #{:halted})

(def supervision-transitions
  "Valid supervision transitions. Map of [from-state event] -> to-state."
  {[:nominal :warning-detected]              :warning
   [:nominal :operator-paused]               :paused-by-supervisor
   [:nominal :operator-escalated]            :awaiting-operator
   [:nominal :execution-failed]              :awaiting-operator
   [:nominal :execution-completed]           :halted
   [:nominal :execution-cancelled]           :halted

   [:warning :warning-cleared]               :nominal
   [:warning :operator-paused]               :paused-by-supervisor
   [:warning :operator-escalated]            :awaiting-operator
   [:warning :execution-failed]              :awaiting-operator
   [:warning :execution-completed]           :halted
   [:warning :execution-cancelled]           :halted

   [:paused-by-supervisor :operator-resumed] :nominal
   [:paused-by-supervisor :operator-escalated] :awaiting-operator
   [:paused-by-supervisor :execution-failed] :awaiting-operator
   [:paused-by-supervisor :execution-completed] :halted
   [:paused-by-supervisor :execution-cancelled] :halted

   [:awaiting-operator :operator-cleared]    :nominal
   [:awaiting-operator :operator-paused]     :paused-by-supervisor
   [:awaiting-operator :execution-completed] :halted
   [:awaiting-operator :execution-cancelled] :halted})

(def supervision-machine-config
  "Data-driven machine config used by the shared fsm component."
  {:fsm/id :workflow-supervision
   :fsm/initial :nominal
   :fsm/context {}
   :fsm/states
   {:nominal              {:on {:warning-detected :warning
                                :operator-paused :paused-by-supervisor
                                :operator-escalated :awaiting-operator
                                :execution-failed :awaiting-operator
                                :execution-completed :halted
                                :execution-cancelled :halted}}
    :warning              {:on {:warning-cleared :nominal
                                :operator-paused :paused-by-supervisor
                                :operator-escalated :awaiting-operator
                                :execution-failed :awaiting-operator
                                :execution-completed :halted
                                :execution-cancelled :halted}}
    :paused-by-supervisor {:on {:operator-resumed :nominal
                                :operator-escalated :awaiting-operator
                                :execution-failed :awaiting-operator
                                :execution-completed :halted
                                :execution-cancelled :halted}}
    :awaiting-operator    {:on {:operator-cleared :nominal
                                :operator-paused :paused-by-supervisor
                                :execution-completed :halted
                                :execution-cancelled :halted}}
    :halted               {:type :final}}})

(def supervision-machine
  "Compiled supervision state machine."
  (fsm/define-machine supervision-machine-config))

(defn- transition-error
  [error message]
  {:success? false
   :error error
   :message message})

;------------------------------------------------------------------------------ Layer 1
;; Legacy-style helper API

(defn valid-state?
  "Check if a supervision state keyword is valid."
  [state]
  (contains? supervision-states state))

(defn terminal-state?
  "Check if a supervision state is terminal."
  [state]
  (contains? terminal-states state))

(defn valid-transition?
  "Check if a supervision transition is valid."
  [current-state event]
  (contains? supervision-transitions [current-state event]))

(defn next-state
  "Get the next state for a supervision transition."
  [current-state event]
  (get supervision-transitions [current-state event]))

(defn transition
  "Attempt a pure supervision transition.

   Returns:
   - {:success? true :state new-state}
   - {:success? false :error keyword :message string}"
  ([current-state event]
   (transition current-state event (constantly true)))

  ([current-state event guard-fn]
   (cond
     (terminal-state? current-state)
     (transition-error
      :terminal-state
      (str "Cannot transition from terminal state: " current-state))

     (not (valid-state? current-state))
     (transition-error
      :invalid-state
      (str "Invalid supervision state: " current-state))

     (not (guard-fn current-state event))
     (transition-error
      :guard-failed
      "Guard function rejected supervision transition")

     (not (valid-transition? current-state event))
     (transition-error
      :invalid-transition
      (str "No supervision transition defined for ["
           current-state " " event "]"))

     :else
     (let [state-map {:_state current-state}
           new-state-map (fsm/transition supervision-machine state-map event)]
       {:success? true
        :state (fsm/current-state new-state-map)}))))

(defn initialize
  "Initialize supervision state."
  []
  (fsm/initialize supervision-machine))

(defn transition-fsm
  "Transition supervision state using the compiled shared FSM."
  [state event]
  (fsm/transition supervision-machine state event))

(defn current-state
  "Read the current supervision state keyword."
  [state]
  (fsm/current-state state))

(defn is-final?
  "Check if the compiled machine state is final."
  [state]
  (fsm/final? supervision-machine state))

(defn get-available-events
  "List valid events from `current-state`."
  [current-state]
  (vec (keep (fn [[[from-state event] _to-state]]
               (when (= from-state current-state)
                 event))
             supervision-transitions)))

(defn machine-graph
  "Return a graph-style view of the supervision machine."
  []
  {:states supervision-states
   :transitions (vec (map (fn [[[from event] to]]
                           {:from from :event event :to to})
                         supervision-transitions))
   :terminal-states terminal-states})
