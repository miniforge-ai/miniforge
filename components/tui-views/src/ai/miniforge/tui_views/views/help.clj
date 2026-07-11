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
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.messages :as msg]
   [ai.miniforge.tui-views.palette :as palette]))

;------------------------------------------------------------------------------ Layer 0
;; Help content

(defn help-sections
  "Keybinding help content organized by section."
  []
  [[(msg/t :help/section-navigation)
    [["j / k / ↓ / ↑" (msg/t :help/nav-move-cursor)]
     ["g / G"          (msg/t :help/nav-jump-top-bottom)]
     ["h / l"          (msg/t :help/nav-back-detail)]
     ["Enter"          (msg/t :help/nav-enter-detail-confirm)]
     ["Tab / Shift-Tab" (msg/t :help/nav-cycle-panes-views)]
     ["1-9"            (msg/t :help/nav-switch-view-number)]]]
   [(msg/t :help/section-selection)
    [["Space"  (msg/t :help/sel-toggle)]
     ["v"      (msg/t :help/sel-visual-mode)]
     ["a"      (msg/t :help/sel-all)]
     ["c"      (msg/t :help/sel-clear)]]]
   [(msg/t :help/section-commands)
    [[":"      (msg/t :help/cmd-command-mode)]
     ["/"      (msg/t :help/cmd-search-mode)]
     ["n / N"  (msg/t :help/cmd-next-prev-match)]
     ["Tab"    (msg/t :help/cmd-tab-complete)]]]
   [(msg/t :help/section-actions)
    [["r"      (msg/t :help/act-refresh-sync)]
     ["s"      (msg/t :help/act-sync-prs)]
     ["b"      (msg/t :help/act-browse-kanban)]
     ["e"      (msg/t :help/act-evidence-view)]
     ["o"      (msg/t :help/act-open-browse)]
     ["x"      (msg/t :help/act-remove-repos)]
     ["?"      (msg/t :help/act-toggle-help)]
     ["q"      (msg/t :help/act-quit)]]]])

(defn format-help-lines
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
    (help-sections)))

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
      {:title (msg/t :help/box-title) :border :single
       :fg palette/status-info
       :content-fn
       (fn [[ic ir]]
         (layout/text [ic ir]
           (str/join "\n" (take ir lines))
           {:fg :default}))})))
