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
   [ai.miniforge.web-dashboard.messages :as messages]
   [ai.miniforge.web-dashboard.views.dashboard :as dashboard]
   [ai.miniforge.web-dashboard.views.fleet :as fleet]
   [ai.miniforge.web-dashboard.views.dag :as dag]
   [ai.miniforge.web-dashboard.views.evidence :as evidence]
   [ai.miniforge.web-dashboard.views.workflows :as workflows]
   [ai.miniforge.web-dashboard.views.archived :as archived]
   [ai.miniforge.web-dashboard.views.control-plane :as control-plane]))

;------------------------------------------------------------------------------ Layer 0
;; Layout and shared utilities

(def ^:private stylesheet-path
  "/css/app.css")

(def ^:private htmx-script-path
  "/js/htmx.min.js")

(def ^:private htmx-ws-script-path
  "/js/htmx-ws.js")

(def ^:private app-script-path
  "/js/app.js")

(def ^:private filter-script-paths
  ["/js/filters/runtime.js"
   "/js/filters/state.js"
   "/js/filters/persistence.js"
   "/js/filters/apply.js"
   "/js/filters/ui.js"
   "/js/filters/init.js"])

(def ^:private sidebar-nav-items
  [{:pane :dashboard     :href "/"              :label-key :layout/nav-dashboard}
   {:pane :fleet         :href "/fleet"         :label-key :layout/nav-pr-fleet}
   {:pane :task-status   :href "/dag"           :label-key :layout/nav-task-status}
   {:pane :evidence      :href "/evidence"      :label-key :layout/nav-evidence}
   {:pane :workflows     :href "/workflows"     :label-key :layout/nav-workflows}
   {:pane :control-plane :href "/control-plane" :label-key :layout/nav-control-plane}])

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

(defn- page-head
  [title]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:title (messages/t :layout/page-title {:title title})]
   [:link {:rel "stylesheet" :href stylesheet-path}]
   [:script {:src htmx-script-path}]
   [:script {:src htmx-ws-script-path}]])

(defn- event-item
  [{:keys [icon text]}]
  [:div.event-item
   [:span.event-icon icon]
   [:span.event-text text]])

(defn- event-banner
  []
  (let [event-items [{:icon "⚡" :text (messages/t :layout/event-build-passing)}
                     {:icon "✓" :text (messages/t :layout/event-prs-merged)}]]
    (into [:div.event-scroll {:id "event-banner"}]
          (mapv event-item event-items))))

(defn- banner-left
  []
  [:div.banner-left
   [:div.logo-container
    [:img.banner-logo {:src "/img/miniforge_logo.png"
                       :alt "Miniforge"}]
    [:div.logo-tagline (messages/t :layout/logo-tagline)]]])

(defn- share-button
  []
  [:button.btn.btn-sm.btn-ghost
   {:onclick "window.miniforge.filters && window.miniforge.filters.shareCurrentView()"
    :title (messages/t :layout/share-title)}
   (messages/t :layout/share-label)])

(defn- theme-button
  []
  [:button.btn.btn-sm.btn-ghost
   {:onclick "window.miniforge.cycleTheme()"
    :title (messages/t :layout/theme-title)}
   (messages/t :layout/theme-label)])

(defn- refresh-button
  []
  [:button.btn.btn-sm.btn-ghost
   {:onclick "location.reload()"}
   (messages/t :layout/refresh-label)])

(defn- banner-right
  []
  [:div.banner-right
   [:div.ws-status
    [:span#ws-indicator.status-dot.disconnected]
    [:span#ws-text.ws-text (messages/t :layout/ws-connected)]]
   (share-button)
   (theme-button)
   (refresh-button)])

(defn- top-banner
  []
  [:header.top-banner
   [:div.banner-content
    (banner-left)
    [:div.banner-center (event-banner)]
    (banner-right)]])

(defn- clear-filters-button
  []
  [:button.btn.btn-sm.btn-ghost
   {:onclick "window.miniforge.filters && window.miniforge.filters.clearFilters('global')"
    :title (messages/t :layout/clear-filters-title)}
   (messages/t :layout/clear-label)])

(defn- add-filter-button
  []
  [:button.btn.btn-sm.btn-ghost.filter-add
   {:hx-get "/api/filter-fields?scope=global"
    :hx-target "#filter-modal-container"
    :hx-swap "innerHTML"
    :title (messages/t :layout/add-filter-title)}
   (messages/t :action/filter)])

(defn- save-view-button
  []
  [:button.btn.btn-sm.btn-ghost
   {:onclick (messages/t :layout/save-view-onclick)
    :title (messages/t :layout/save-view-title)}
   (messages/t :layout/save-view-label)])

(defn- global-filter-bar
  []
  [:div.global-filter-bar
   [:span.global-filter-bar-label (messages/t :layout/global-filters-label)]
   [:div#global-filter-chips.filter-chips]
   [:div.filter-actions
    (clear-filters-button)
    (add-filter-button)
    (save-view-button)]])

(defn- nav-item
  [current-pane {:keys [pane href label-key]}]
  (let [active-class (when (= current-pane pane) "active")
        label (messages/t label-key)]
    [:a.nav-item {:href href
                  :class active-class}
     [:span.icon "▸"] label]))

(defn- sidebar-nav
  [current-pane]
  [:aside.sidebar
   (into [:nav.nav]
         (mapv #(nav-item current-pane %) sidebar-nav-items))])

(defn- page-main
  [title body]
  [:main.main
   [:div.page-header
    [:h1.page-title title]]
   (into [:div.content] body)])

(defn- dashboard-shell
  [title body]
  (let [current-pane (title->pane title)]
    [:div.dashboard
     (sidebar-nav current-pane)
     (page-main title body)]))

(defn- filter-modal-container
  []
  [:div#filter-modal-container])

(defn- footer-scripts
  []
  (into [[:script {:src app-script-path}]]
        (mapv (fn [path] [:script {:src path}]) filter-script-paths)))

(defn- page-body
  [title body]
  (let [current-pane-name (name (title->pane title))]
    (into [:body {:data-current-pane current-pane-name}
           (top-banner)
           (global-filter-bar)
           (dashboard-shell title body)
           (filter-modal-container)]
          (footer-scripts))))

(defn layout
  "Main page layout with htmx and WebSocket."
  [title & body]
  (page/html5
   (page-head title)
   (page-body title body)))

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

;; Control Plane views
(def agents-grid-fragment control-plane/agents-grid-fragment)
(def decision-queue-fragment control-plane/decision-queue-fragment)
(defn control-plane-view [agents decisions stats]
  (layout "Control Plane"
          (control-plane/control-plane-content agents decisions stats)))
