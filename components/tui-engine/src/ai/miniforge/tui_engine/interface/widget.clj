;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.tui-engine.interface.widget
  "Public widget API for tui-engine.

   Re-exports composite widgets so that consuming components can depend
   on the interface boundary rather than internal namespaces."
  (:require
   [ai.miniforge.tui-engine.widget :as widget]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Widgets

(def status-indicator
  "Render a single status indicator character with color.
   Signature: [status]
   status: :running :success :failed :blocked :pending :skipped :spinning
   Returns 1x1 cell buffer."
  widget/status-indicator)

(def status-text
  "Render status indicator followed by label text.
   Signature: [[cols rows] status label]
   Returns cell buffer of [cols 1]."
  widget/status-text)

(def progress-bar
  "Render a progress bar with optional percentage.
   Signature: [[cols rows] opts]
   opts: {:percent :fill-fg :empty-fg :show-pct?}
   Returns cell buffer."
  widget/progress-bar)

(def tree
  "Render a tree with expand/collapse navigation.
   Signature: [[cols rows] opts]
   opts: {:nodes :expanded :selected :fg :selected-fg :selected-bg}
   nodes: [{:label :depth :expandable?}]
   Returns cell buffer."
  widget/tree)

(def kanban
  "Render kanban-style columns.
   Signature: [[cols rows] opts]
   opts: {:columns}
   columns: [{:title :color :cards}]
   cards: [{:label :status}]
   Returns cell buffer."
  widget/kanban)

(def scrollable
  "Render a scrollable viewport with scrollbar.
   Signature: [[cols rows] opts]
   opts: {:lines :offset :fg :bg}
   lines: vector of strings
   Returns cell buffer."
  widget/scrollable)

(def sparkline
  "Render a braille-based mini sparkline chart.
   Signature: [[cols rows] opts]
   opts: {:values :fg}
   values: vector of numbers
   Returns cell buffer."
  widget/sparkline)
