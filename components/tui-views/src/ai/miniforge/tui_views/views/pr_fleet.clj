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

(ns ai.miniforge.tui-views.views.pr-fleet
  "PR Fleet view -- shows all tracked pull requests across repositories.

   Displays a table of PRs with title, readiness score, risk level,
   CI status, and repo information."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.update.navigation :as nav]))

;------------------------------------------------------------------------------ Layer 0
;; Rendering helpers

(defn risk-indicator [risk]
  (case risk
    :low    "LOW"
    :medium "MED"
    :high   "HIGH"
    "---"))

(defn readiness-bar [readiness cols]
  (let [pct (int (* (or readiness 0) 100))
        bar-width (max 1 (- cols 5))
        filled (int (* bar-width (or readiness 0)))]
    (str (apply str (repeat filled "█"))
         (apply str (repeat (- bar-width filled) "░"))
         " " pct "%")))

(defn format-pr-row [pr cols]
  {:title (or (:pr/title pr) "Untitled")
   :repo  (or (:pr/repo pr) "")
   :readiness (readiness-bar (:pr/readiness pr) (min 15 (max 8 (quot cols 6))))
   :risk (risk-indicator (:pr/risk pr))})

(defn render-title-bar [[cols rows]]
  (layout/text [cols rows] " MINIFORGE │ PR Fleet"
               {:fg :cyan :bold? true}))

(defn render-table [pr-items selected [cols rows]]
  (if (empty? pr-items)
    (layout/text [cols rows] "  No PRs tracked. Use :add-repo to add repositories."
                 {:fg :default})
    (let [title-width (max 10 (- cols 60))]
      (layout/table [cols rows]
        {:columns [{:key :title :header "PR Title" :width title-width}
                   {:key :repo :header "Repository" :width 25}
                   {:key :readiness :header "Readiness" :width 18}
                   {:key :risk :header "Risk" :width 6}]
         :data (mapv #(format-pr-row % cols) pr-items)
         :selected-row selected}))))

(defn render-footer [flash-message [cols rows]]
  (layout/text [cols rows]
    (str " j/k:navigate  Enter:detail  r:sync  b:kanban  ::cmd  q:quit"
         (when flash-message (str "  │ " flash-message)))
    {:fg :default}))

(defn render
  "Render the PR fleet view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [pr-items (nav/visible-prs model)
        selected (:selected-idx model)
        flash (:flash-message model)]
    (layout/split-v [cols rows] (/ 2.0 rows)
      (fn [size] (render-title-bar size))
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [size] (render-table pr-items selected size))
          (fn [size] (render-footer flash size)))))))
