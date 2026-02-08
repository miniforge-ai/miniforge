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

(ns ai.miniforge.tui-views.view
  "View dispatch: model -> cell buffer.

   Routes to the correct view renderer based on :view key in model.
   Composes the view tab bar, command/search bar, and active view."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.views.workflow-list :as wf-list]
   [ai.miniforge.tui-views.views.workflow-detail :as wf-detail]
   [ai.miniforge.tui-views.views.evidence :as evidence]
   [ai.miniforge.tui-views.views.artifact-browser :as artifact-browser]
   [ai.miniforge.tui-views.views.dag-kanban :as dag-kanban]))

;------------------------------------------------------------------------------ Layer 0
;; View dispatch

(defn- render-active-view
  "Dispatch to the active view renderer."
  [model size]
  (case (:view model)
    :workflow-list    (wf-list/render model size)
    :workflow-detail  (wf-detail/render model size)
    :evidence         (evidence/render model size)
    :artifact-browser (artifact-browser/render model size)
    :dag-kanban       (dag-kanban/render model size)
    ;; Fallback
    (layout/text size "Unknown view")))

;------------------------------------------------------------------------------ Layer 1
;; Command/search bar overlay

(defn- render-command-bar
  "Render the command or search input bar at the bottom of the screen."
  [[cols _rows] model]
  (when (not= :normal (:mode model))
    (layout/text [cols 1] (:command-buf model)
                 {:fg :white :bg :default :bold? true})))

;------------------------------------------------------------------------------ Layer 2
;; Root view function

(defn root-view
  "Root view function for the Elm runtime.
   model -> [cols rows] -> cell-buffer"
  [model [cols rows]]
  (if (< rows 4)
    (layout/text [cols rows] "Terminal too small" {:fg :red})
    (let [;; Reserve 1 row for command bar if in command/search mode
          cmd-active? (not= :normal (:mode model))
          content-rows (if cmd-active? (dec rows) rows)
          ;; Render main view
          content (render-active-view model [cols content-rows])]
      (if cmd-active?
        ;; Build full-size buffer: content on top, command bar on last row
        (let [buf (layout/make-buffer [cols rows])
              buf (layout/blit buf content 0 0)
              cmd-bar (render-command-bar [cols 1] model)]
          (layout/blit buf cmd-bar 0 (dec rows)))
        content))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m (model/init-model))
  (layout/buf->strings (root-view m [80 24]))
  :leave-this-here)
