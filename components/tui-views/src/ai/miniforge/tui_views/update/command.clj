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

(ns ai.miniforge.tui-views.update.command
  "Command mode routing — stub.
   Full implementation in feat/tui-command-completion PR."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Stub: command dispatch (no-op until command system lands)

(defn execute-command
  "Execute a command string. Stub — returns model with flash message."
  [model cmd-str]
  (assoc model :flash-message (str "Command not yet available: " cmd-str)))

(defn execute-confirmed-action
  "Execute confirmed action. Stub — no-op."
  [model]
  model)

(defn complete-command-name
  "Return matching command names for a partial input. Stub — empty."
  [_partial]
  [])

(defn compute-completions
  "Given a command buffer, return completions. Stub — nil."
  [_model _cmd-buf]
  nil)

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
