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
  "Shared tab bar renderer for all top-level views.

   Renders: MINIFORGE │ [PR Fleet (1)] Workflows (2) ...
   Active tab is highlighted with brackets and bold styling."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.model :as model]))

;------------------------------------------------------------------------------ Layer 0
;; Tab bar rendering

(defn- tab-label
  "Render a single tab label, active tab gets brackets and bold."
  [idx view-kw active?]
  (let [label (get model/view-labels view-kw (name view-kw))
        text (str label " (" idx ")")]
    (if active?
      (str "[" text "]")
      (str " " text " "))))

(defn render
  "Render the shared tab bar.
   Arguments:
   - model:    full app model (reads :view for active highlight)
   - context:  optional context string (e.g. repo count, PR count)
   - [cols rows]: available screen area (typically 1 row)
   Returns: cell buffer [cols rows]"
  [model context [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        active-view (:view model)
        header-fg (get theme :header :cyan)
        active-fg (get theme :selected-fg :white)
        active-bg (get theme :selected-bg :blue)
        ;; Build tab string
        tabs-str (apply str
                   (map-indexed
                    (fn [idx v]
                      (tab-label (inc idx) v (= v active-view)))
                    model/top-level-views))
        bar-str (str " MINIFORGE │" tabs-str
                     (when (seq context) (str " │ " context)))
        ;; Truncate to fit
        bar-str (subs bar-str 0 (min (count bar-str) cols))
        ;; Write the base string first (non-highlighted)
        buffer (layout/buf-put-string (layout/make-buffer [cols rows]) 0 0 bar-str
                                      {:fg header-fg :bg :default :bold? false})
        ;; Now highlight the active tab bracket section
        prefix " MINIFORGE │"
        before-active (apply str
                        (map-indexed
                         (fn [idx v]
                           (tab-label (inc idx) v (= v active-view)))
                         (take-while #(not= % active-view)
                                     model/top-level-views)))
        raw-active-idx (.indexOf model/top-level-views active-view)
        active-idx (if (neg? raw-active-idx) 1 (inc raw-active-idx))
        active-start (+ (count prefix) (count before-active))
        active-label (tab-label active-idx active-view true)
        active-end (+ active-start (count active-label))]
    (reduce (fn [b col]
              (if (and (>= col active-start) (< col active-end) (< col cols))
                (let [cell (get-in b [0 col])]
                  (assoc-in b [0 col]
                            (assoc cell :fg active-fg :bg active-bg :bold? true)))
                b))
            buffer
            (range active-start (min active-end cols)))))
