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

(ns ai.miniforge.tui-engine.widget
  "Composite TUI widgets built on layout primitives.

   Convenience namespace that re-exports all widget functions from stratified
   sub-namespaces (status, tree, scroll). Each widget is a pure function:
   (widget-fn [cols rows] opts) -> cell-buffer.

   Widgets compose with layout/blit and layout/split-h/v."
  (:require
   [ai.miniforge.tui-engine.widget.status :as status]
   [ai.miniforge.tui-engine.widget.tree :as tree]
   [ai.miniforge.tui-engine.widget.scroll :as scroll]))

;; Re-export from status
(def status-indicator status/status-indicator)
(def status-text status/status-text)
(def progress-bar status/progress-bar)

;; Re-export from tree
(def tree tree/tree)
(def kanban tree/kanban)

;; Re-export from scroll
(def scrollable scroll/scrollable)
(def sparkline scroll/sparkline)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.tui-engine.layout :as layout])

  (layout/buf->strings (progress-bar [20 1] {:percent 40}))
  ;; => ["████████░░░░░░░░ 40%"]

  (layout/buf->strings (status-text [20 1] :running "Generating code"))
  ;; => ["● Generating code   "]

  (layout/buf->strings (sparkline [10 1] {:values [1 3 5 2 8 4 6 9 3 7]}))

  :leave-this-here)
