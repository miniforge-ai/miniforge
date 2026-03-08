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

(ns ai.miniforge.tui-views.views.tab-bar
  "Tab bar renderer for top-level views.

   Renders a single-line header showing the MINIFORGE brand, the active
   view label (highlighted), and optional context text (counts, timestamps).

   Layer 0: Pure rendering, depends only on layout and model data."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.palette :as palette]))

(defn render
  "Render the tab bar header.
   model: app model (uses :view for active tab)
   context: optional context string (e.g. workflow count, PR summary)
   [cols rows]: available size (typically [cols 2])"
  [model context [cols rows]]
  (let [active-view (:view model)
        label (get model/view-labels active-view "MINIFORGE")
        text (str " MINIFORGE │ " label
                  (when (seq context) (str " │ " context)))]
    (layout/text [cols rows] text {:fg palette/status-info :bold? true})))
