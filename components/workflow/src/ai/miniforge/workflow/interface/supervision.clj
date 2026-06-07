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

(ns ai.miniforge.workflow.interface.supervision
  "Public API for the per-run workflow supervision machine."
  (:require
   [ai.miniforge.workflow.supervision :as supervision]))

;------------------------------------------------------------------------------ Layer 0
;; State and transition definitions

(def supervision-states
  "Set of all per-run supervision state keywords (keys of the machine config)."
  supervision/supervision-states)

(def terminal-states
  "Set of supervision state keywords whose config type is :final."
  supervision/terminal-states)

(def supervision-transitions
  "Map of [from-state event] -> to-state for the supervision machine."
  supervision/supervision-transitions)

;------------------------------------------------------------------------------ Layer 1
;; State helpers

(def valid-state?
  "Predicate: is the keyword a known supervision state? Args: [state].
   Returns boolean."
  supervision/valid-state?)

(def terminal-state?
  "Predicate: is the supervision state terminal? Args: [state]. Returns
   boolean."
  supervision/terminal-state?)

(def valid-transition?
  "Predicate: is [current-state event] a defined transition? Args:
   [current-state event]. Returns boolean."
  supervision/valid-transition?)

(def next-state
  "Resolve the target state for a transition. Args: [current-state event].
   Returns the to-state keyword, or nil when no such transition exists."
  supervision/next-state)

(def transition
  "Attempt a pure supervision transition (optionally guarded). Args:
   [current-state event] or [current-state event guard-fn]. Returns
   {:success? true :state new-state} on success, or
   {:success? false :error keyword :message string} on rejection."
  supervision/transition)

(def get-available-events
  "List events available from a state. Args: [current-state]. Returns a
   vector of event keywords."
  supervision/get-available-events)

(def machine-graph
  "Graph-style view of the supervision machine. No args. Returns
   {:states set :transitions [{:from :event :to} ...] :terminal-states set}."
  supervision/machine-graph)

;------------------------------------------------------------------------------ Layer 2
;; Shared FSM surface

(def initialize
  "Initialize a compiled-FSM supervision state. No args. Returns the
   shared-FSM state map at the machine's initial state."
  supervision/initialize)

(def transition-fsm
  "Transition the compiled-FSM supervision state. Args: [state event].
   Returns the new shared-FSM state map."
  supervision/transition-fsm)

(def current-state
  "Read the current state keyword from a compiled-FSM state map. Args:
   [state]. Returns the state keyword."
  supervision/current-state)

(def is-final?
  "Predicate: is the compiled-FSM state map in a final state? Args:
   [state]. Returns boolean."
  supervision/is-final?)
