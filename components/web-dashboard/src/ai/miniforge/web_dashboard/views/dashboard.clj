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

(ns ai.miniforge.web-dashboard.views.dashboard
  "Dashboard view components with high information density."
  (:require
   [hiccup2.core :refer [html]]
   [ai.miniforge.web-dashboard.components :as c]))

;------------------------------------------------------------------------------ Layer 0
;; Dashboard fragments

(defn stats-fragment
  "Stats cards fragment for htmx updates."
  [stats]
  (html
   [:div.stats-grid
    (c/stat-card (str (get-in stats [:workflows :running] 0))
                 "Running Workflows"
                 {:trend (if (> (get-in stats [:workflows :running] 0) 0) :up :neutral)
                  :href "/workflows"})
    (c/stat-card (str (get-in stats [:workflows :total] 0))
                 "Total Workflows"
                 {:trend (if (> (get-in stats [:workflows :completed] 0) 0) :up :neutral)
                  :href "/workflows"})
    (c/stat-card (str (get-in stats [:trains :active]))
                 "Active Trains"
                 {:trend (if (> (get-in stats [:trains :active]) 0) :up :neutral)
                  :href "/fleet"})
    (c/stat-card (str (get-in stats [:health :critical]))
                 "Critical Risks"
                 {:trend (if (> (get-in stats [:health :critical]) 0) :down :neutral)
                  :href "/dag"})]))

(defn risk-analysis-fragment
  "Risk analysis fragment for htmx updates."
  [risk-data]
  (html
   [:div.risk-panel
    [:h3.section-title "AI Risk Analysis"]
    [:div.risk-summary
     [:div.risk-badge.risk-high (str (get-in risk-data [:summary :high]) " High")]
     [:div.risk-badge.risk-medium (str (get-in risk-data [:summary :medium]) " Medium")]
     [:div.risk-badge.risk-low (str (get-in risk-data [:summary :low]) " Low")]]
    [:div.risk-list
     (for [risk (take 5 (:risks risk-data))]
       [:div.risk-item {:class (str "risk-" (name (:risk-level risk)))}
        [:div.risk-header
         [:span.risk-score (:risk-score risk)]
         [:span.risk-name (:train-name risk)]]
        [:div.risk-factors
         (for [factor (:factors risk)]
           [:span.risk-factor {:class (str "severity-" (name (:severity factor)))}
            (str (name (:type factor)) ": " (:count factor))])]])]]))

(defn activity-fragment
  "Recent activity fragment for htmx updates."
  [activities]
  (html
   [:div.activity-feed
    [:h3.section-title "Recent Activity"]
    [:div.activity-list
     (for [activity (take 10 activities)]
       [:div.activity-item
        [:span.activity-time
         (if (:timestamp activity)
           (str (.format (java.text.SimpleDateFormat. "HH:mm:ss")
                        (:timestamp activity)))
           "—")]
        [:span.activity-message (:message activity)]
        [:a.activity-link {:href (str "/train/" (:train-id activity))} "→"]])]]))

(defn workflow-summary-fragment
  "Workflow summary fragment for dashboard."
  [workflows]
  (html
   [:div.workflow-summary
    [:h3.section-title (str "Workflows (" (count workflows) ")")]
    (if (empty? workflows)
      [:div.empty-state [:p "No workflows running"]]
      [:div.workflow-summary-list
       (for [wf (take 5 workflows)]
         [:div.workflow-summary-item
          {:class (str "wf-status-" (name (or (:status wf) :unknown)))}
          [:div.wf-summary-header
           [:a.wf-summary-name {:href (str "/workflow/" (:id wf))}
            (or (:name wf) "Unnamed")]
           [:span.wf-summary-badge
            {:class (str "badge-" (name (or (:status wf) :unknown)))}
            (name (or (:status wf) :unknown))]]
          [:div.wf-summary-detail
           [:span.wf-phase (str "Phase: " (or (:phase wf) "—"))]
           [:div.wf-progress-bar
            [:div.wf-progress-fill
             {:style (str "width:" (or (:progress wf) 0) "%")}]]]])])]))

(defn fleet-grid-fragment
  "Fleet status grid fragment for htmx updates."
  [fleet-state]
  (html
   [:div.fleet-grid
    [:h3.section-title (str "Fleet Status (" (count (:trains fleet-state)) " trains)")]
    [:div.repo-groups
     (for [[repo prs] (take 10 (:repos fleet-state))]
       [:div.repo-group
        [:div.repo-name repo]
        [:div.pr-count (str (count prs) " PRs")]])]]))

;------------------------------------------------------------------------------ Layer 1
;; Main dashboard view

(defn dashboard-view
  "Main dashboard view with high information density."
  [layout state]
  (layout "Dashboard"
   [:div.dashboard-grid
    ;; Stats section
    [:section#stats-section.section
     {:hx-get "/api/stats"
      :hx-trigger "refresh from:body, every 10s"
      :hx-swap "innerHTML"
      :data-filter-refresh "true"}
     (stats-fragment (:stats state))]

    ;; Workflow summary
    [:section#workflows-summary-section.section
     {:hx-get "/api/dashboard/workflows"
      :hx-trigger "refresh from:body, every 10s"
      :hx-swap "innerHTML"
      :data-filter-refresh "true"}
     (workflow-summary-fragment (:workflows state))]

    ;; Risk analysis section
    [:section#risk-section.section
     {:hx-get "/api/risk"
      :hx-trigger "refresh from:body, every 30s"
      :hx-swap "innerHTML"
      :data-filter-refresh "true"}
     (risk-analysis-fragment (:risk state))]

    ;; Fleet overview
    [:section#fleet-section.section
     {:hx-get "/api/fleet/grid"
      :hx-trigger "refresh from:body, every 15s"
      :hx-swap "innerHTML"
      :data-filter-refresh "true"}
     (fleet-grid-fragment (:fleet state))]

    ;; Recent activity
    [:section#activity-section.section
     {:hx-get "/api/activity"
      :hx-trigger "refresh from:body, every 10s"
      :hx-swap "innerHTML"
      :data-filter-refresh "true"}
     (activity-fragment (:activity state))]]))
