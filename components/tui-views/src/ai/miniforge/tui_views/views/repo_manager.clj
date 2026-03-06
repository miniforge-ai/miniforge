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

(ns ai.miniforge.tui-views.views.repo-manager
  "Repo manager view -- add/remove repositories from the fleet.

   Shows configured fleet repos or browse-mode remote repos.
   Supports selection for batch add/remove operations."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.model :as model]))

;------------------------------------------------------------------------------ Layer 0
;; Rendering helpers

(defn source-label [model]
  (if (= :browse (:repo-manager-source model))
    "Browse"
    "Fleet"))

(defn format-repo-row [repo selected-ids]
  (let [selected? (contains? (or selected-ids #{}) repo)]
    {:marker (if selected? "●" " ")
     :repo repo}))

(defn view-number
  "1-based view number for the tab bar display."
  []
  (let [idx (.indexOf ^java.util.List model/top-level-views :repo-manager)]
    (when (>= idx 0) (inc idx))))

(defn render-title-bar [model [cols rows]]
  (let [items (model/repo-manager-items model)
        vnum (view-number)
        label (str " MINIFORGE │ [Repos (" vnum ")] "
                   (source-label model) " — "
                   (count items) " repo(s)")]
    (layout/text [cols rows] label
                 {:fg :cyan :bold? true})))

(defn render-table [items selected selected-ids [cols rows]]
  (if (empty? items)
    (layout/text [cols rows] "  No repositories configured. Use :add-repo or b to browse."
                 {:fg :default})
    (layout/table [cols rows]
      {:columns [{:key :marker :header " " :width 2}
                 {:key :repo :header "Repository" :width (max 10 (- cols 8))}]
       :data (mapv #(format-repo-row % selected-ids) items)
       :selected-row selected})))

(defn render-footer [model [cols rows]]
  (let [source (if (= :browse (:repo-manager-source model)) :browse :fleet)]
    (layout/text [cols rows]
      (if (= :browse source)
        " Enter:add  space:select  f:fleet  Esc:back  q:quit"
        " b:browse  x:remove  space:select  a:all  /:search  q:quit")
      {:fg :default})))

(defn render
  "Render the repo manager view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [items (model/repo-manager-items model)
        selected (:selected-idx model)
        selected-ids (:selected-ids model)]
    (layout/split-v [cols rows] (/ 2.0 rows)
      (fn [size] (render-title-bar model size))
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [size] (render-table items selected selected-ids size))
          (fn [size] (render-footer model size)))))))
