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

(ns ai.miniforge.tui-views.views.dag-kanban
  "DAG Kanban view -- N5 Section 3.2.5.

   Shows tasks from the dependency graph as kanban columns:
   - BLOCKED: Tasks with unsatisfied dependencies
   - PENDING: Tasks ready to execute
   - RUNNING: Tasks currently being executed by agents
   - DONE: Completed tasks

   Columns are derived from task states. Each card shows the task name,
   assigned agent, and dependency status."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-engine.interface.widget :as widget]
   [ai.miniforge.tui-views.views.tab-bar :as tab-bar]))

;------------------------------------------------------------------------------ Layer 0
;; Task grouping

(defn- group-tasks-by-status
  "Group workflow tasks into 6 kanban columns matching N2 task states."
  [workflows]
  (let [all-wfs (or workflows [])
        blocked   (filterv #(= :blocked (:status %)) all-wfs)
        ready     (filterv #(#{:ready :pending} (:status %)) all-wfs)
        active    (filterv #(#{:running :implementing :pr-opening :responding} (:status %)) all-wfs)
        in-review (filterv #(#{:ci-running :review-pending} (:status %)) all-wfs)
        merging   (filterv #(#{:ready-to-merge :merging} (:status %)) all-wfs)
        done      (filterv #(#{:merged :success :completed :failed :skipped} (:status %)) all-wfs)]
    [{:title "BLOCKED"
      :color :red
      :cards (mapv (fn [wf] {:label (:name wf) :status :blocked}) blocked)}
     {:title "READY"
      :color :yellow
      :cards (mapv (fn [wf] {:label (:name wf) :status :ready}) ready)}
     {:title "ACTIVE"
      :color :cyan
      :cards (mapv (fn [wf] {:label (:name wf) :status :running}) active)}
     {:title "IN REVIEW"
      :color :magenta
      :cards (mapv (fn [wf] {:label (:name wf) :status :review}) in-review)}
     {:title "MERGING"
      :color :blue
      :cards (mapv (fn [wf] {:label (:name wf) :status :merging}) merging)}
     {:title "DONE"
      :color :green
      :cards (mapv (fn [wf] {:label (:name wf) :status (or (:status wf) :success)}) done)}]))

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn render
  "Render the DAG kanban view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        columns (group-tasks-by-status (:workflows model))]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Tab bar
      (fn [[c r]]
        (tab-bar/render model nil [c r]))
      ;; Content + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          ;; Kanban board
          (fn [[kc kr]]
            (layout/box [kc kr]
              {:title "Task Board" :border :single :fg (get theme :border :default)
               :content-fn
               (fn [[ic ir]]
                 (widget/kanban [ic ir] {:columns columns}))}))
          ;; Footer
          (fn [[fc fr]]
            (layout/text [fc fr]
              " Tab:views  1-0:jump  q:quit"
              {:fg (get theme :fg-dim :default)})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
