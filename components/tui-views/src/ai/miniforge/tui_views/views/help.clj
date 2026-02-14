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

(ns ai.miniforge.tui-views.views.help
  "Help overlay showing key bindings.

   Rendered as a centered box on top of the active view when
   :help-visible? is true in the model."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Help text content

(def ^:private help-lines
  ["Miniforge TUI -- Key Bindings"
   ""
   "Navigation"
   "  j/k  Up/Down   Move cursor"
   "  g/G            Jump to top/bottom"
   "  Enter/l        Drill into detail"
   "  Esc/h          Go back"
   ""
   "Views"
   "  1  Workflows   2  Detail"
   "  3  Evidence    4  Artifacts"
   "  5  DAG Kanban  6  PR Fleet"
   "  7  PR Detail   8  Train"
   "  b  Kanban      e  Evidence"
   "  a  Artifacts"
   ""
   "Modes"
   "  :   Command mode"
   "  /   Search (filters list)"
   "  ?   Toggle this help"
   "  Space  Expand/collapse"
   ""
   "Actions"
   "  r   Refresh data"
   "  q   Quit"
   ""
   "Press ? or Esc to close"])

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn render
  "Render the help overlay as a centered box.
   Returns a cell buffer sized to [cols rows]."
  [[cols rows]]
  (let [box-w (min 42 (- cols 4))
        box-h (min (+ 2 (count help-lines)) (- rows 2))
        x-off (max 0 (quot (- cols box-w) 2))
        y-off (max 0 (quot (- rows box-h) 2))
        overlay-buf (layout/box [box-w box-h]
                      {:title "Help" :border :single :fg :cyan
                       :content-fn
                       (fn [[ic ir]]
                         (let [buf (layout/make-buffer [ic ir])
                               visible (take ir help-lines)]
                           (reduce (fn [b [idx line]]
                                     (let [truncated (subs line 0 (min (count line) ic))]
                                       (layout/buf-put-string b 0 idx truncated
                                                              {:fg :white :bg :black :bold? false})))
                                   buf
                                   (map-indexed vector visible))))})
        full-buf (layout/make-buffer [cols rows])]
    (layout/blit full-buf overlay-buf x-off y-off)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (layout/buf->strings (render [80 24]))
  :leave-this-here)
