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

(ns ai.miniforge.adapter-claude-code.interface
  "Claude Code adapter for the control plane.

   Discovers local Claude Code sessions, polls their status,
   and delivers decisions via file-drop mechanism.

   Layer 0: Adapter creation"
  (:require
   [ai.miniforge.adapter-claude-code.impl :as impl]))

;------------------------------------------------------------------------------ Layer 0
;; Factory

(defn create-adapter
  "Create a Claude Code adapter instance.

   Options:
   - :projects-dir - Override default Claude projects directory

   Example:
     (def adapter (create-adapter))"
  [& [config]]
  (impl/create-adapter (or config {})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def adapter (create-adapter))
  :end)
