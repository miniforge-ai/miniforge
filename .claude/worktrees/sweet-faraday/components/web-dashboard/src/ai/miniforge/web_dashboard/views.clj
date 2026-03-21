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

(ns ai.miniforge.web-dashboard.views
  "Production-ready server-side rendered views for web dashboard.

   Tableau-inspired dense visualization with terminal aesthetic.
   No build step required - pure server-side rendering."
  (:require
   [hiccup.page :as page]
   [ai.miniforge.web-dashboard.views.dashboard :as dashboard]
   [ai.miniforge.web-dashboard.views.fleet :as fleet]
   [ai.miniforge.web-dashboard.views.dag :as dag]
   [ai.miniforge.web-dashboard.views.evidence :as evidence]
   [ai.miniforge.web-dashboard.views.workflows :as workflows]
   [ai.miniforge.web-dashboard.views.archived :as archived]))

;------------------------------------------------------------------------------ Layer 0
;; Layout and shared utilities

(defn title->pane
  "Convert page title to pane keyword for filter context."
  [title]
  (case title
    "Task Status" :task-status
    "PR Fleet" :fleet
    "Evidence" :evidence
    "Workflows" :workflows
    "Dashboard" :dashboard
    :task-status))

(defn layout
  "Main page layout with htmx and WebSocket."
  [title & body]
  (let [current-pane (title->pane title)]
    (page/html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title (str "Miniforge | " title)]
    [:link {:rel "stylesheet" :href "/css/app.css"}]
    [:script {:src "/js/htmx.min.js"}]
    [:script {:src "/js/htmx-ws.js"}]]
   [:body {:data-current-pane (name current-pane)}
    ;; Top banner with large logo and actions
    [:header.top-banner
     [:div.banner-content
      [:div.banner-left
       [:div.logo-container
        [:img.banner-logo {:src "/img/miniforge_logo.png"
                           :alt "Miniforge"}]
        [:div.logo-tagline "an industrial software factory that fits on your desk"]]]
      [:div.banner-center
       ;; Optional: Event status scroll area
       [:div.event-scroll
        [:div.event-item
         [:span.event-icon "⚡"]
         [:span.event-text "Build passing"]]
        [:div.event-item
         [:span.event-icon "✓"]
         [:span.event-text "3 PRs merged"]]]]
      [:div.banner-right
       [:div.ws-status
        [:span#ws-indicator.status-dot.disconnected]
        [:span#ws-text.ws-text "Connected"]]
       [:button.btn.btn-sm.btn-ghost
        {:onclick "window.miniforge.filters && window.miniforge.filters.shareCurrentView()"
         :title "Share or bookmark current view"}
        "🔗 Share"]
       [:button.btn.btn-sm.btn-ghost
        {:onclick "window.miniforge.cycleTheme()"
         :title "Cycle theme (Ctrl+Shift+T)"}
        "◐ Theme"]
       [:button.btn.btn-sm.btn-ghost
        {:onclick "location.reload()"}
        "↻ Refresh"]]]]
    ;; Global filter bar
    [:div.global-filter-bar
     [:span.global-filter-bar-label "Global Filters:"]
     [:div#global-filter-chips.filter-chips]
     [:div.filter-actions
      [:button.btn.btn-sm.btn-ghost
       {:onclick "window.miniforge.filters && window.miniforge.filters.clearFilters('global')"
        :title "Clear global filters"}
       "Clear"]
      [:button.btn.btn-sm.btn-ghost.filter-add
       {:hx-get "/api/filter-fields?scope=global"
        :hx-target "#filter-modal-container"
        :hx-swap "innerHTML"
        :title "Add global filter"}
       "Filter"]
      [:button.btn.btn-sm.btn-ghost
       {:onclick "const name = prompt('Save view as:'); if (name && window.miniforge.filters) window.miniforge.filters.saveView(name)"
        :title "Save current filter set"}
       "💾 Save View"]]]
    ;; Sidebar + Main content area
    [:div.dashboard
     [:aside.sidebar
      [:nav.nav
       [:a.nav-item {:href "/"
                     :class (when (= title "Dashboard") "active")}
        [:span.icon "▸"] "Dashboard"]
       [:a.nav-item {:href "/fleet"
                     :class (when (= title "PR Fleet") "active")}
        [:span.icon "▸"] "PR Fleet"]
       [:a.nav-item {:href "/dag"
                     :class (when (= title "Task Status") "active")}
        [:span.icon "▸"] "Task Status"]
       [:a.nav-item {:href "/evidence"
                     :class (when (= title "Evidence") "active")}
        [:span.icon "▸"] "Evidence"]
       [:a.nav-item {:href "/workflows"
                     :class (when (= title "Workflows") "active")}
        [:span.icon "▸"] "Workflows"]]]
     [:main.main
      [:div.page-header
       [:h1.page-title title]]
      (into [:div.content] body)]]]
    ;; Filter modal container
    [:div#filter-modal-container]
    [:script {:src "/js/app.js"}]
    [:script {:src "/js/filters/runtime.js"}]
    [:script {:src "/js/filters/state.js"}]
    [:script {:src "/js/filters/persistence.js"}]
    [:script {:src "/js/filters/apply.js"}]
    [:script {:src "/js/filters/ui.js"}]
    [:script {:src "/js/filters/init.js"}])))

;------------------------------------------------------------------------------ Layer 1
;; Public API - Re-exports from subdirectories

;; Dashboard views
(def stats-fragment dashboard/stats-fragment)
(def risk-analysis-fragment dashboard/risk-analysis-fragment)
(def activity-fragment dashboard/activity-fragment)
(def fleet-grid-fragment dashboard/fleet-grid-fragment)
(defn dashboard-view [state]
  (dashboard/dashboard-view layout state))

;; Fleet views
(def train-list-fragment fleet/train-list-fragment)
(defn fleet-view [fleet-state]
  (fleet/fleet-view layout fleet-state))
(defn train-detail-view [train]
  (fleet/train-detail-view layout train))

;; DAG views
(def filter-modal-fragment dag/filter-modal-fragment)
(defn dag-kanban-view [state]
  (dag/dag-kanban-view layout state))

;; Evidence views
(def evidence-list-fragment evidence/evidence-list-fragment)
(defn evidence-view [state]
  (evidence/evidence-view layout state))

;; Workflow views
(def workflow-list-fragment workflows/workflow-list-fragment)
(def workflow-summary-fragment dashboard/workflow-summary-fragment)
(def workflow-events-fragment workflows/workflow-events-fragment)
(def workflow-detail-panel workflows/workflow-detail-panel)
(def archived-workflow-list-fragment archived/archived-workflow-list-fragment)
(defn workflows-view [wfs]
  (workflows/workflows-view layout wfs))
(defn workflow-detail-view [workflow events]
  (workflows/workflow-detail-view layout workflow events))
