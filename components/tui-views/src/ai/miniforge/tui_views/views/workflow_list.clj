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

(ns ai.miniforge.tui-views.views.workflow-list
  "Workflow list view -- N5 Section 3.2.1.

   Shows all active and recent workflows in a table with:
   - Status indicator (colored dot)
   - Workflow name
   - Current phase
   - Progress bar
   - Agent status"
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Rendering helpers

(defn- status-char [status]
  (case status
    :running "●"
    :success "✓"
    :failed  "✗"
    :blocked "◐"
    "○"))

(defn- format-progress-bar
  "Create a Unicode text progress bar for table cells."
  [pct width]
  (let [pct (or pct 0)
        bar-w (max 1 (- width 5))
        filled (int (/ (* pct bar-w) 100))]
    (str (apply str (repeat filled \u2588))
         (apply str (repeat (- bar-w filled) \u2591))
         (format " %3d%%" pct))))

(defn- format-workflow-row
  "Transform workflow data into table row format."
  [wf]
  {:status-char (status-char (:status wf))
   :name (:name wf)
   :phase (some-> (:phase wf) name)
   :progress-str (format-progress-bar (:progress wf 0) 20)
   :agent-msg (when-let [agent (first (vals (:agents wf)))]
                (when-let [msg (:message agent)]
                  (subs msg 0 (min 16 (count msg)))))})

(defn- filter-workflows
  "Filter workflows by search indices, or return all if no filter active."
  [workflows filtered-indices]
  (if filtered-indices
    (vec (keep-indexed (fn [i wf] (when (contains? filtered-indices i) wf)) workflows))
    workflows))

(defn- render-title-bar [workflow-count last-updated [cols rows]]
  (layout/text [cols rows]
    (str " MINIFORGE │ Workflows [" workflow-count "]"
         (when last-updated
           (str " │ " (.format (java.text.SimpleDateFormat. "HH:mm:ss") last-updated))))
    {:fg :cyan :bold? true}))

(defn- render-table [workflows selected [cols rows]]
  (if (empty? workflows)
    (layout/text [cols rows] "  No active workflows. Waiting for events..."
                 {:fg :default})
    (layout/table [cols rows]
      {:columns [{:key :status-char :header "  " :width 2}
                 {:key :name :header "Workflow" :width (max 10 (- cols 50))}
                 {:key :phase :header "Phase" :width 12}
                 {:key :progress-str :header "Progress" :width 20}
                 {:key :agent-msg :header "Agent" :width 16}]
       :data (mapv format-workflow-row workflows)
       :selected-row selected})))

(defn- render-footer [flash-message [cols rows]]
  (layout/text [cols rows]
    (str " j/k:navigate  Enter:detail  1-5:views  /:search  ::cmd  q:quit"
         (when flash-message (str "  │ " flash-message)))
    {:fg :default}))

(defn render
  "Render the workflow list view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [all-workflows (:workflows model)
        workflows (filter-workflows all-workflows (:filtered-indices model))
        selected (:selected-idx model)
        flash (:flash-message model)]
    (layout/split-v [cols rows] (/ 2.0 rows)
      (fn [size] (render-title-bar (count all-workflows) (:last-updated model) size))
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [size] (render-table workflows selected size))
          (fn [size] (render-footer flash size)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m {:workflows [{:name "deploy-v2" :status :running :phase :implement :progress 40}]
          :selected-idx 0})
  (layout/buf->strings (render m [80 24]))
  :leave-this-here)
