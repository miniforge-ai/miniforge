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
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Machine definition

(def ^:private supervision-config-resource
  "Classpath location of the per-run supervision machine config."
  "config/workflow/supervision.edn")

(defn- load-supervision-machine-config
  []
  (if-let [resource (io/resource supervision-config-resource)]
    (:workflow/supervision (edn/read-string (slurp resource)))
    (throw (ex-info "Missing workflow supervision config resource"
                    {:resource supervision-config-resource}))))

(def supervision-machine-config
  "Data-driven machine config used by the shared fsm component."
  (load-supervision-machine-config))

(defn- state-definitions
  []
  (:fsm/states supervision-machine-config))

(def supervision-states
  "Known per-run supervision states per
   I-WORKFLOW-SUPERVISION-MACHINE-ARCHITECTURE §3.2."
  (set (keys (state-definitions))))

(def terminal-states
  "Supervision terminal states."
  (->> (state-definitions)
       (keep (fn [[state definition]]
               (when (= :final (:type definition))
                 state)))
       set))

(def supervision-transitions
  "Valid supervision transitions. Map of [from-state event] -> to-state."
  (->> (state-definitions)
       (mapcat (fn [[from-state definition]]
                 (map (fn [[event to-state]]
                        [[from-state event] to-state])
                      (:on definition))))
       (into {})))

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
      (messages/t :supervision/terminal-state {:state current-state}))

     (not (valid-state? current-state))
     (transition-error
      :invalid-state
      (messages/t :supervision/invalid-state {:state current-state}))

     (not (guard-fn current-state event))
     (transition-error
      :guard-failed
      (messages/t :supervision/guard-rejected))

     (not (valid-transition? current-state event))
     (transition-error
      :invalid-transition
      (messages/t :supervision/invalid-transition
                  {:state current-state
                   :event event}))

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
