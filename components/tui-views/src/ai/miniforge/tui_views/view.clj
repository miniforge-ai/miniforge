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

(ns ai.miniforge.tui-views.view
  "View dispatch: model -> cell buffer.

   Uses the declarative view-spec interpreter (screens.edn) for all views.
   Composes overlays (command bar, help, completion popup, confirmation)
   as a pipeline of named overlay functions."
  (:require
   [ai.miniforge.tui-engine.interface :as engine]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.palette :as palette]
   [ai.miniforge.tui-views.view.interpret :as interpret]
   [ai.miniforge.tui-views.view.overlays :as overlays]))

;------------------------------------------------------------------------------ Layer 0
;; View dispatch — data-driven via screen specs

(defn render-active-view
  "Render the active view using the declarative screen spec interpreter."
  [model theme size]
  (if-let [spec (interpret/get-screen-spec (:view model))]
    (interpret/render-screen model theme size spec)
    (layout/text size "Unknown view")))

;------------------------------------------------------------------------------ Layer 1
;; Theme post-processing

(defn apply-theme-bg
  "Replace :bg :default cells with the theme's background color."
  [buffer theme]
  (let [bg (:bg theme :default)]
    (if (= :default bg)
      buffer
      (mapv (fn [row]
              (mapv (fn [cell]
                      (if (= :default (:bg cell))
                        (assoc cell :bg bg)
                        cell))
                    row))
            buffer))))

;------------------------------------------------------------------------------ Layer 2
;; Root view function

(defn root-view
  "Root view function for the Elm runtime.
   Resolves theme, renders the active view via the spec interpreter,
   then applies overlays as a pipeline.
   model -> [cols rows] -> cell-buffer"
  [model [cols rows]]
  (if (< rows 4)
    (layout/text [cols rows] "Terminal too small" {:fg palette/status-fail})
    (let [theme (engine/get-theme (:theme model))
          model (assoc model :resolved-theme theme)
          cmd-active? (not= :normal (:mode model))
          content-rows (if cmd-active? (dec rows) rows)
          ;; Render main content via spec interpreter
          content (render-active-view model theme [cols content-rows])
          ;; Start with a full-size buffer if command bar is active
          base (if cmd-active?
                 (layout/blit (layout/make-buffer [cols rows]) content 0 0)
                 content)]
      ;; Overlay pipeline: each step gets the buffer and can modify it
      (-> base
          (overlays/overlay-command-bar model theme [cols rows])
          (overlays/overlay-completions model theme [cols rows])
          (overlays/overlay-help model theme [cols rows])
          (overlays/overlay-confirm model theme [cols rows])
          (apply-theme-bg theme)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m (ai.miniforge.tui-views.model/init-model))
  (layout/buf->strings (root-view m [80 24]))
  :leave-this-here)
