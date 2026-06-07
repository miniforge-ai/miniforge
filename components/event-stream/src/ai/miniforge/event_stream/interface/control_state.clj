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

(ns ai.miniforge.event-stream.interface.control-state
  "Control-state helpers for the event-stream public API."
  (:require
   [ai.miniforge.event-stream.control :as control]))

;------------------------------------------------------------------------------ Layer 0
;; Control state primitives

(def create-control-state
  "Create a control-state atom for driving workflow pause/resume/cancel
   from the CLI poller or TUI. Returns an atom holding
   {:paused false :stopped false :adjustments {}}."
  control/create-control-state)

(def pause!
  "Set the control-state atom's :paused flag true. Returns the new
   state map."
  control/pause!)

(def resume!
  "Clear the control-state atom's :paused flag (set false). Returns the
   new state map."
  control/resume!)

(def cancel!
  "Set the control-state atom's :stopped flag true. Returns the new
   state map."
  control/cancel!)

(def paused?
  "Return the boolean :paused flag from a control-state atom."
  control/paused?)

(def cancelled?
  "Return the boolean :stopped flag from a control-state atom."
  control/cancelled?)
