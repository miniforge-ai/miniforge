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

(ns ai.miniforge.tui-views.update.completion
  "Tab-completion state machine — stub.
   Full implementation in feat/tui-command-completion PR.")

;------------------------------------------------------------------------------ Layer 0
;; Stub: completion (no-op until command+completion system lands)

(defn handle-tab
  "Handle Tab in command mode. Stub — no-op."
  [model] model)

(defn handle-shift-tab
  "Handle Shift+Tab in command mode. Stub — no-op."
  [model] model)

(defn dismiss
  "Dismiss completion popup. Stub — no-op."
  [model] model)

(defn accept
  "Accept selected completion. Stub — no-op."
  [model] model)

(defn next-completion
  "Cycle to next completion. Stub — no-op."
  [model] model)

(defn prev-completion
  "Cycle to previous completion. Stub — no-op."
  [model] model)

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
