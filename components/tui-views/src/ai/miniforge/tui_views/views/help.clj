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
  "Help overlay -- shows keybinding reference.

   Renders a centered overlay box with key bindings and their descriptions.
   Displayed when :help-visible? is true in model."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Help content

(def ^:private help-sections
  "Keybinding help content organized by section."
  [["Navigation"
    [["j / k / ↓ / ↑" "Move cursor down / up"]
     ["g / G"          "Jump to top / bottom"]
     ["h / l"          "Go back / Enter detail"]
     ["Enter"          "Enter detail or confirm"]
     ["Tab / Shift-Tab" "Cycle panes or views"]
     ["1-9"            "Switch to view by number"]]]
   ["Selection"
    [["Space"  "Toggle selection"]
     ["v"      "Enter visual mode"]
     ["a"      "Select all"]
     ["c"      "Clear selection"]]]
   ["Commands"
    [[":"      "Enter command mode"]
     ["/"      "Enter search mode"]
     ["n / N"  "Next / previous search match"]
     ["Tab"    "Tab-complete command or argument"]]]
   ["Actions"
    [["r"      "Refresh / sync"]
     ["s"      "Sync PRs"]
     ["b"      "Browse repos / kanban"]
     ["e"      "Evidence view"]
     ["o"      "Open browse"]
     ["x"      "Remove repos"]
     ["?"      "Toggle this help"]
     ["q"      "Quit"]]]])

(defn- format-help-lines
  "Build flat lines from help sections."
  []
  (into []
    (mapcat
      (fn [[section-title bindings]]
        (concat
          [(str "  " section-title)]
          (map (fn [[key desc]]
                 (str "    " (format "%-18s %s" key desc)))
               bindings)
          [""])))
    help-sections))

;------------------------------------------------------------------------------ Layer 1
;; Overlay rendering

(defn render-overlay
  "Render the help overlay centered on the screen.
   Returns a cell buffer to be blitted on top of the main view."
  [[cols rows]]
  (let [lines (format-help-lines)
        box-w (min (- cols 4) 52)
        box-h (min (- rows 2) (+ (count lines) 2))]
    (layout/box [box-w box-h]
      {:title "Help — Key Bindings" :border :single
       :fg :cyan
       :content-fn
       (fn [[ic ir]]
         (layout/text [ic ir]
           (str/join "\n" (take ir lines))
           {:fg :white}))})))
