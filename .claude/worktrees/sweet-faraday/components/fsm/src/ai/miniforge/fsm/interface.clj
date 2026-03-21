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

(ns ai.miniforge.fsm.interface
  "Finite State Machine public interface.

   Provides declarative state machine definitions for workflows, phases,
   and other stateful processes in miniforge.

   Example usage:
     (def machine (define-machine workflow-config))
     (def state (initialize machine))
     (def state' (transition machine state :event))
     (current-state state') ;; => :new-state"
  (:require
   [ai.miniforge.fsm.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Machine definition

(def define-machine
  "Create a state machine definition from config.

   Config format:
   {:fsm/id       :machine-name
    :fsm/initial  :initial-state
    :fsm/context  {:key value}  ; optional initial context
    :fsm/states
    {:state-a {:on {:event-1 :state-b
                    :event-2 {:target :state-c
                              :guard guard-fn
                              :actions [action-fn]}}}
     :state-b {:entry entry-action
               :exit exit-action
               :on {...}}
     :state-c {:type :final}}}

   Returns compiled machine definition."
  core/define-machine)

;------------------------------------------------------------------------------ Layer 1
;; State operations

(def initialize
  "Initialize a machine, returning the initial state.

   Example:
     (def state (initialize machine))"
  core/initialize)

(def transition
  "Transition given an event.

   Arguments:
     machine - Compiled machine
     state   - Current state
     event   - Event keyword or {:type :event :data ...}

   Example:
     (transition machine state :complete)
     (transition machine state {:type :fail :data {:error \"timeout\"}})"
  core/transition)

(def current-state
  "Get current state keyword from state map.

   Example:
     (current-state state) ;; => :running"
  core/current-state)

(def context
  "Get context data from state map.

   Example:
     (context state) ;; => {:phase-index 2}"
  core/context)

(def in-state?
  "Check if machine is in a specific state.

   Example:
     (in-state? state :running) ;; => true"
  core/in-state?)

(def final?
  "Check if current state is a final state.

   Example:
     (final? machine state) ;; => false"
  core/final?)

;------------------------------------------------------------------------------ Layer 2
;; Context manipulation

(def assign
  "Create action that assigns values to context.

   Values can be constants or functions (context, event) -> value.

   Example:
     (assign {:count inc
              :last-event (fn [ctx event] (:type event))})"
  core/assign)

(def update-context
  "Update context with a function.

   Example:
     (update-context state assoc :key value)"
  core/update-context)

;------------------------------------------------------------------------------ Layer 3
;; Guard helpers

(def guard
  "Create a guard from predicate (context, event) -> boolean.

   Example:
     (guard (fn [ctx event] (< (:attempts ctx) 3)))"
  core/guard)

(def all-guards
  "Combine guards with AND logic.

   Example:
     (all-guards has-budget? has-permission?)"
  core/all-guards)

(def any-guard
  "Combine guards with OR logic.

   Example:
     (any-guard is-admin? is-owner?)"
  core/any-guard)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Simple traffic light example
  (def traffic-light
    (define-machine
     {:fsm/id :traffic-light
      :fsm/initial :red
      :fsm/states
      {:red    {:on {:timer :green}}
       :green  {:on {:timer :yellow}}
       :yellow {:on {:timer :red}}}}))

  (def s (initialize traffic-light))
  (current-state s) ;; => :red

  (def s' (transition traffic-light s :timer))
  (current-state s') ;; => :green

  :leave-this-here)
