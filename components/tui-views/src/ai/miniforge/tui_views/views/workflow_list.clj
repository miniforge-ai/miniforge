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
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.views.tab-bar :as tab-bar]))

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

(defn- render-title-bar [model workflow-count last-updated [cols rows]]
  (let [context (str "[" workflow-count "]"
                     (when last-updated
                       (str " " (.format (java.text.SimpleDateFormat. "HH:mm:ss") last-updated))))]
    (tab-bar/render model context [cols rows])))

(defn- render-table [workflows selected selected-ids theme [cols rows]]
  (if (empty? workflows)
    (layout/text [cols rows] "  No active workflows. Waiting for events..."
                 {:fg (get theme :fg :default)})
    (let [show-sel? (seq selected-ids)
          sel-w (if show-sel? 3 0)
          data (mapv (fn [wf]
                       (cond-> (format-workflow-row wf)
                         show-sel?
                         (assoc :sel (if (contains? selected-ids (:id wf)) "[x]" "[ ]"))))
                     workflows)
          columns (cond-> []
                    show-sel? (conj {:key :sel :header "   " :width 3})
                    true (into [{:key :status-char :header "  " :width 2}
                                {:key :name :header "Workflow"
                                 :width (max 10 (- cols 50 sel-w))}
                                {:key :phase :header "Phase" :width 12}
                                {:key :progress-str :header "Progress" :width 20}
                                {:key :agent-msg :header "Agent" :width 16}]))]
      (layout/table [cols rows]
        {:columns columns :data data :selected-row selected
         :header-fg (get theme :header :cyan)
         :row-fg (get theme :row-fg :default)
         :row-bg (get theme :row-bg :default)
         :selected-fg (get theme :selected-fg :white)
         :selected-bg (get theme :selected-bg :blue)}))))

(defn- render-footer [model [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        sel-count (count (:selected-ids model))
        visual? (:visual-anchor model)
        filtered? (:filtered-indices model)
        filter-count (when filtered? (count filtered?))
        flash (:flash-message model)
        filter-tag (when filtered?
                     (str "FILTER [" filter-count "] │ "))]
    (layout/text [cols rows]
      (cond
        (pos? sel-count)
        (str " " (when filter-tag filter-tag)
             sel-count " selected │ Space:toggle  v:visual  a:all  c:clear  :archive :delete :cancel"
             (when flash (str "  │ " flash)))

        visual?
        (str " " (when filter-tag filter-tag)
             "VISUAL │ j/k:extend  Space:toggle  Esc:exit  a:all"
             (when flash (str "  │ " flash)))

        filtered?
        (str " " filter-tag
             "Space:select  a:all  v:visual  Esc:clear filter"
             (when flash (str "  │ " flash)))

        :else
        (str " j/k:nav  Enter:detail  Space:select  v:visual  1-0:views  /:search  ::cmd  q:quit"
             (when flash (str "  │ " flash))))
      {:fg (get theme :fg-dim :default)})))

(defn render
  "Render the workflow list view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        all-workflows (:workflows model)
        workflows (filter-workflows all-workflows (:filtered-indices model))
        selected (:selected-idx model)
        selected-ids (or (:selected-ids model) #{})]
    (layout/split-v [cols rows] (/ 2.0 rows)
      (fn [size] (render-title-bar model (count all-workflows) (:last-updated model) size))
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [size] (render-table workflows selected selected-ids theme size))
          (fn [size] (render-footer model size)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m {:workflows [{:name "deploy-v2" :status :running :phase :implement :progress 40}]
          :selected-idx 0})
  (layout/buf->strings (render m [80 24]))
  :leave-this-here)
