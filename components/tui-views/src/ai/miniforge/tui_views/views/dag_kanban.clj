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
   [ai.miniforge.tui-engine.interface.widget :as widget]))

;------------------------------------------------------------------------------ Layer 0
;; Task grouping

(defn column-title
  "Column title with item count."
  [label cards]
  (if (seq cards)
    (str label " (" (count cards) ")")
    label))

(defn group-tasks-by-status
  "Group workflow tasks into kanban columns."
  [workflows]
  (let [all-wfs (or workflows [])
        blocked (filterv #(= :blocked (:status %)) all-wfs)
        pending (filterv #(= :pending (:status %)) all-wfs)
        running (filterv #(= :running (:status %)) all-wfs)
        stale   (filterv #(= :stale (:status %)) all-wfs)
        done    (filterv #(#{:success :completed} (:status %)) all-wfs)
        failed  (filterv #(= :failed (:status %)) all-wfs)
        done-cards (vec (concat
                         (mapv (fn [wf] {:label (:name wf) :status :success}) done)
                         (mapv (fn [wf] {:label (:name wf) :status :failed}) failed)
                         (mapv (fn [wf] {:label (:name wf) :status :stale}) stale)))
        blocked-cards (mapv (fn [wf] {:label (:name wf) :status :blocked}) blocked)
        pending-cards (mapv (fn [wf] {:label (:name wf) :status :pending}) pending)
        running-cards (mapv (fn [wf] {:label (:name wf) :status :running}) running)]
    [{:title (column-title "BLOCKED" blocked-cards)
      :color [220 50 40]
      :cards blocked-cards}
     {:title (column-title "PENDING" pending-cards)
      :color [200 160 0]
      :cards pending-cards}
     {:title (column-title "RUNNING" running-cards)
      :color [0 150 180]
      :cards running-cards}
     {:title (column-title "DONE" done-cards)
      :color [0 180 80]
      :cards done-cards}]))

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn render
  "Render the DAG kanban view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [columns (group-tasks-by-status (:workflows model))]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Title bar
      (fn [[c r]]
        (layout/text [c r] " MINIFORGE │ DAG Kanban"
                     {:fg [0 150 180] :bold? true}))
      ;; Content + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          ;; Kanban board
          (fn [[kc kr]]
            (layout/box [kc kr]
              {:title "Task Board" :border :single :fg :default
               :content-fn
               (fn [[ic ir]]
                 (widget/kanban [ic ir] {:columns columns}))}))
          ;; Footer
          (fn [[fc fr]]
            (layout/text [fc fr]
              " 1:workflows  2:detail  3:evidence  4:artifacts  q:quit"
              {:fg :default})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
