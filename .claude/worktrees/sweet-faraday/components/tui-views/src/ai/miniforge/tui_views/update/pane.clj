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

(ns ai.miniforge.tui-views.update.pane
  "Pane focus, selection, and cycling for multi-pane detail views.

   Pure functions for PR-detail (and other multi-pane) pane navigation.
   Layers 0-1.")

;------------------------------------------------------------------------------ Layer 0
;; Pane predicates and per-pane selection helpers

(defn pane-detail-view?
  "Views where j/k should navigate per-pane selection."
  [view]
  (= :pr-detail view))

(defn pane-count
  "Return the number of panes for the given view, or 1 for single-pane views."
  [view]
  (case view
    :workflow-detail 2   ;; phases | agent output
    :pr-detail       5   ;; summary | readiness | risk | gates | chat
    :dag-kanban      6   ;; 6 kanban columns
    1))

(defn focused-pane
  "Return the currently focused pane index (default 0)."
  [model]
  (get-in model [:detail :focused-pane] 0))

(defn pane-navigate-up
  "Move the per-pane selection cursor up in the focused pane."
  [model]
  (let [pane (focused-pane model)]
    (update-in model [:detail :pane-selections pane] #(max 0 (dec (or % 0))))))

(defn pane-navigate-down
  "Move the per-pane selection cursor down in the focused pane."
  [model]
  (let [pane (focused-pane model)]
    (update-in model [:detail :pane-selections pane] #(inc (or % 0)))))

(defn pane-navigate-top
  "Jump the per-pane selection cursor to the top of the focused pane."
  [model]
  (let [pane (focused-pane model)]
    (assoc-in model [:detail :pane-selections pane] 0)))

(defn pane-navigate-bottom
  "Jump the per-pane selection cursor to the bottom of the focused pane.
   Sets a high value; the tree renderer clamps naturally."
  [model]
  (let [pane (focused-pane model)]
    (update-in model [:detail :pane-selections pane] (constantly 999))))

(defn init-pane-state
  "Initialize pane focus and per-pane selections on a model entering a
   multi-pane detail view (e.g. pr-detail)."
  [model]
  (-> model
      (assoc-in [:detail :focused-pane] 0)
      (assoc-in [:detail :pane-selections] {0 0, 1 0, 2 0, 3 0})))

;------------------------------------------------------------------------------ Layer 1
;; Pane cycling

(defn cycle-pane
  "Cycle Tab focus between panes in multi-pane views.
   Views with multiple columns/panes define their pane count;
   single-pane views are a no-op.
   Saves the current pane's selected-idx and restores the next pane's."
  [model]
  (let [n       (pane-count (:view model))
        current (focused-pane model)
        next-p  (mod (inc current) n)]
    (assoc-in model [:detail :focused-pane] next-p)))

(defn cycle-pane-reverse
  "Cycle Shift+Tab focus between panes in reverse."
  [model]
  (let [n       (pane-count (:view model))
        current (focused-pane model)]
    (assoc-in model [:detail :focused-pane]
              (mod (+ current (dec n)) n))))
