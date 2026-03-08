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

(ns ai.miniforge.tui-views.views.train-view
  "Train view -- shows PRs ordered for merge in a release train.

   Displays the train name and a table of PRs in merge order
   with readiness and status."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.palette :as palette]))

;------------------------------------------------------------------------------ Layer 0
;; Rendering helpers

(defn format-pr-row [pr]
  {:title (or (:pr/title pr) "Untitled")
   :readiness (str (int (* 100 (or (:pr/readiness pr) 0))) "%")
   :order (str (or (:pr/merge-order pr) "—"))})

(defn render-title-bar [train [cols rows]]
  (layout/text [cols rows]
    (str " MINIFORGE │ Train: "
         (or (:train/name train) "Release Train"))
    {:fg palette/status-info :bold? true}))

(defn render-table [prs selected [cols rows]]
  (if (empty? prs)
    (layout/text [cols rows] "  No PRs in this train."
                 {:fg :default})
    (let [visible-count (max 0 (- rows 2))
          offset (let [sel (or selected 0)]
                   (if (<= (inc sel) visible-count) 0
                       (inc (- sel visible-count))))]
      (layout/table [cols rows]
        {:columns [{:key :order :header "#" :width 4}
                   {:key :title :header "PR Title" :width (max 10 (- cols 30))}
                   {:key :readiness :header "Ready" :width 8}]
         :data (mapv format-pr-row
                 (sort-by :pr/merge-order prs))
         :selected-row selected
         :offset offset}))))

(defn render-footer [[cols rows]]
  (layout/text [cols rows]
    " Esc:back  j/k:navigate  space:select  q:quit"
    {:fg :default}))

(defn render
  "Render the train view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [train (get-in model [:detail :selected-train])
        prs (or (:train/prs train) [])
        selected (:selected-idx model)]
    (layout/split-v [cols rows] (/ 2.0 rows)
      (fn [size] (render-title-bar train size))
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [size] (render-table prs selected size))
          render-footer)))))
