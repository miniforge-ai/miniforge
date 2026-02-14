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
  "Train view -- N5 Section 3.2.10 / N9.

   Shows ordered train members with merge readiness status.
   PRs are displayed in merge order with dependency information."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- format-train-pr-row [pr]
  {:order  (str (:pr/merge-order pr "?"))
   :repo   (or (:pr/repo pr) "")
   :number (str "#" (:pr/number pr))
   :title  (or (:pr/title pr) "")
   :status (some-> (:pr/status pr) name)
   :ci     (some-> (:pr/ci-status pr) name)
   :deps   (str (count (:pr/depends-on pr [])))
   :blocks (str (count (:pr/blocks pr [])))})

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn render
  "Render the train view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [train    (get-in model [:detail :selected-train])
        prs      (sort-by :pr/merge-order (get train :train/prs []))
        progress (get train :train/progress)
        selected (:selected-idx model)]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Title bar with train name and progress
      (fn [[c r]]
        (layout/text [c r]
          (str " MINIFORGE │ Train: " (or (:train/name train) "?")
               (when progress
                 (str " [" (:merged progress 0) "/" (:total progress 0) " merged]"))
               (when-let [status (:train/status train)]
                 (str " │ " (name status))))
          {:fg :cyan :bold? true}))
      ;; Content + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          ;; Table of PRs in merge order
          (fn [[tc tr]]
            (if (empty? prs)
              (layout/text [tc tr]
                           "  Empty train. No PRs added yet."
                           {:fg :default})
              (layout/table [tc tr]
                {:columns [{:key :order  :header "#"      :width 3}
                           {:key :repo   :header "Repo"   :width 18}
                           {:key :number :header "PR"     :width 6}
                           {:key :title  :header "Title"  :width (max 8 (- tc 58))}
                           {:key :status :header "Status" :width 12}
                           {:key :ci     :header "CI"     :width 7}
                           {:key :deps   :header "Deps"   :width 5}
                           {:key :blocks :header "Blks"   :width 5}]
                 :data (mapv format-train-pr-row prs)
                 :selected-row selected})))
          ;; Footer
          (fn [[fc fr]]
            (layout/text [fc fr]
              " j/k:navigate  Enter:pr-detail  6:fleet  Esc:back  q:quit"
              {:fg :default})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
