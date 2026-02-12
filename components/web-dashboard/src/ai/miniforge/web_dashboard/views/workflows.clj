;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.views.workflows
  "Workflow list and detail views."
  (:require
   [hiccup2.core :refer [html]]
   [ai.miniforge.web-dashboard.components :as c]))

;------------------------------------------------------------------------------ Layer 0
;; Workflow fragments

(defn workflow-list-fragment
  "Workflow list fragment for htmx updates."
  [workflows]
  (html
   (if (empty? workflows)
     [:div.empty-state
      [:div.empty-icon "⚙️"]
      [:h3 "No Workflows Yet"]
      [:p "Workflows will appear here when you run: "]
      [:code.empty-code "miniforge workflow run examples/workflows/simple.edn"]
      [:p.empty-hint "Try running a workflow to see real-time progress tracking."]]
     [:div.workflow-list
      [:table.workflow-table
       [:thead
        [:tr
         [:th "Status"]
         [:th "Name"]
         [:th "Phase"]
         [:th "Progress"]
         [:th "Started"]]]
       [:tbody
        (for [wf workflows]
          [:tr.workflow-row {:onclick (str "location.href='/workflow/" (:id wf) "'")}
           [:td (c/status-dot (:status wf))]
           [:td (:name wf)]
           [:td (:phase wf)]
           [:td (c/progress-bar (:progress wf))]
           [:td (if (:started-at wf)
                  (str (.format (java.text.SimpleDateFormat. "HH:mm") (:started-at wf)))
                  "—")]])]]])))

;------------------------------------------------------------------------------ Layer 1
;; Workflow list view

(defn workflows-view
  "Workflows list page view."
  [layout workflows]
  (layout "Workflows"
   [:div.workflows-page
    [:section.workflows-section
     [:div.workflows-header
      [:span.workflows-count (str (count workflows) " " (if (= 1 (count workflows)) "workflow" "workflows"))]
      ;; Local filter bar for this pane
      [:div.workflows-filter-bar
       [:div#filter-chips.filter-chips]
       [:div.filter-actions
        [:button.btn.btn-sm.btn-ghost.filter-add
         {:hx-get "/api/filter-fields?scope=local&pane=workflows"
          :hx-target "#filter-modal-container"
          :hx-swap "innerHTML"
          :title "Add pane-local filter"}
         "+ Filter"]]]]
     ;; Filter modal container
     [:div#filter-modal-container]
     [:div#workflows-content
      {:hx-get "/api/workflows"
       :hx-trigger "refresh from:body, every 5s"
       :hx-swap "innerHTML"}
      (workflow-list-fragment workflows)]]]))

;------------------------------------------------------------------------------ Layer 2
;; Workflow detail view

(defn workflow-detail-view
  "Detailed workflow view."
  [layout workflow]
  (if (:error workflow)
    (layout "Error" [:div.error (:error workflow)])
    (layout (str "Workflow: " (:name workflow))
     [:div.workflow-detail
      [:div.workflow-header
       [:h2 (:name workflow)]
       [:div.workflow-controls
        [:button.btn.btn-sm
         {:onclick "sendWorkflowCommand('pause')"
          :title "Pause workflow execution"}
         "⏸ Pause"]
        [:button.btn.btn-sm
         {:onclick "sendWorkflowCommand('resume')"
          :title "Resume paused workflow"}
         "▶ Resume"]
        [:button.btn.btn-sm
         {:onclick "sendWorkflowCommand('stop')"
          :title "Stop workflow"
          :style "color: var(--color-status-error);"}
         "⏹ Stop"]]]
      [:div.workflow-info
       [:span (c/badge (name (:status workflow))
                      {:variant (case (:status workflow)
                                 :completed :success
                                 :running :info
                                 :failed :error
                                 :neutral)})]
       [:span (str "Phase: " (:phase workflow))]
       [:span (str "Progress: " (:progress workflow) "%")]]
      [:div.workflow-output
       [:h3 "Output"]
       [:pre.output-text "Workflow output would appear here..."]]])))
