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

(def supervision-states supervision/supervision-states)
(def terminal-states supervision/terminal-states)
(def supervision-transitions supervision/supervision-transitions)

;------------------------------------------------------------------------------ Layer 1
;; State helpers

(def valid-state? supervision/valid-state?)
(def terminal-state? supervision/terminal-state?)
(def valid-transition? supervision/valid-transition?)
(def next-state supervision/next-state)
(def transition supervision/transition)
(def get-available-events supervision/get-available-events)
(def machine-graph supervision/machine-graph)

;------------------------------------------------------------------------------ Layer 2
;; Shared FSM surface

(def initialize supervision/initialize)
(def transition-fsm supervision/transition-fsm)
(def current-state supervision/current-state)
(def is-final? supervision/is-final?)
