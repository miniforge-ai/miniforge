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
   [hiccup2.core :refer [html]]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.components :as c]))

;------------------------------------------------------------------------------ Layer 0
;; Layout

(defn- layout
  "Main page layout with htmx and WebSocket."
  [title & body]
  (page/html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title (str "Miniforge | " title)]
    [:link {:rel "stylesheet" :href "/css/app.css"}]
    [:script {:src "https://unpkg.com/htmx.org@2.0.0"}]
    [:script {:src "https://unpkg.com/htmx-ext-ws@2.0.0/ws.js"}]]
   [:body
    [:div.dashboard
     [:aside.sidebar
      [:div.logo
       [:pre.ascii-logo
        "╔═╗╦╔╗╔╦╔═╗╔═╗╦═╗╔═╗╔═╗
"
        "║║║║║║║║╠╣ ║ ║╠╦╝║ ╦║╣
"
        "╩ ╩╩╝╚╝╩╚  ╚═╝╩╚═╚═╝╚═╝"]]
      [:nav.nav
       [:a.nav-item {:href "/" :class "active"} [:span.icon "▸"] "Dashboard"]
       [:a.nav-item {:href "/fleet"} [:span.icon "▸"] "PR Fleet"]
       [:a.nav-item {:href "/dag"} [:span.icon "▸"] "DAG Kanban"]
       [:a.nav-item {:href "/evidence"} [:span.icon "▸"] "Evidence"]
       [:a.nav-item {:href "/workflows"} [:span.icon "▸"] "Workflows"]]
      [:div.ws-status
       [:span.label "WebSocket:"]
       [:span#ws-indicator.status-dot.disconnected]
       [:span#ws-text "Connecting..."]]]
     [:main.main
      [:header.header
       [:h1.page-title title]
       [:div.header-actions
        [:button.btn.btn-sm.btn-ghost {:onclick "location.reload()"} "↻ Refresh"]]]
      [:div.content body]]]
    [:script {:type "text/javascript"}
     "
     // WebSocket connection for real-time updates
     const wsUrl = 'ws://' + window.location.host + '/ws';
     let ws;
     const indicator = document.getElementById('ws-indicator');
     const wsText = document.getElementById('ws-text');
     
     function connect() {
       ws = new WebSocket(wsUrl);
       
       ws.onopen = () => {
         indicator.className = 'status-dot connected';
         wsText.textContent = 'Live';
       };
       
       ws.onclose = () => {
         indicator.className = 'status-dot disconnected';
         wsText.textContent = 'Disconnected';
         setTimeout(connect, 3000);
       };
       
       ws.onerror = () => {
         indicator.className = 'status-dot error';
         wsText.textContent = 'Error';
       };
       
       ws.onmessage = (event) => {
         const msg = JSON.parse(event.data);
         
         // Trigger htmx refresh for relevant sections
         if (msg['event/type']) {
           if (msg['event/type'].includes('workflow')) {
             htmx.trigger('#workflows-section', 'refresh');
           }
           if (msg.type === 'state') {
             htmx.trigger('#stats-section', 'refresh');
             htmx.trigger('#fleet-section', 'refresh');
             htmx.trigger('#risk-section', 'refresh');
           }
         }
       };
     }
     
     connect();
     "]]))

;------------------------------------------------------------------------------ Layer 1
;; Dashboard view components

(defn stats-fragment
  "Stats cards fragment for htmx updates."
  [stats]
  (html
   [:div.stats-grid
    (c/stat-card (str (get-in stats [:trains :active]))
                 "Active Trains"
                 {:trend (if (> (get-in stats [:trains :active]) 0) :up :neutral)})
    (c/stat-card (str (get-in stats [:prs :ready]))
                 "Ready to Merge"
                 {:trend (if (> (get-in stats [:prs :ready]) 0) :up :neutral)})
    (c/stat-card (str (get-in stats [:prs :blocked]))
                 "Blocked PRs"
                 {:trend (if (> (get-in stats [:prs :blocked]) 0) :down :neutral)})
    (c/stat-card (str (get-in stats [:health :critical]))
                 "Critical Risks"
                 {:trend (if (> (get-in stats [:health :critical]) 0) :down :neutral)})]))

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

(defn dashboard-view
  "Main dashboard view with high information density."
  [state]
  (layout "Dashboard"
   [:div.dashboard-grid
    ;; Stats section
    [:section#stats-section.section
     {:hx-get "/api/stats"
      :hx-trigger "refresh from:body, every 5s"
      :hx-swap "innerHTML"}
     (stats-fragment (:stats state))]
    
    ;; Risk analysis section
    [:section#risk-section.section
     {:hx-get "/api/risk"
      :hx-trigger "refresh from:body, every 10s"
      :hx-swap "innerHTML"}
     (risk-analysis-fragment (:risk state))]
    
    ;; Fleet overview
    [:section#fleet-section.section
     {:hx-get "/api/fleet/grid"
      :hx-trigger "refresh from:body, every 5s"
      :hx-swap "innerHTML"}
     (fleet-grid-fragment (:fleet state))]
    
    ;; Recent activity
    [:section#activity-section.section
     {:hx-get "/api/activity"
      :hx-trigger "refresh from:body, every 3s"
      :hx-swap "innerHTML"}
     (activity-fragment (:activity state))]]))

;------------------------------------------------------------------------------ Layer 2
;; Fleet view (PR train management)

(defn train-list-fragment
  "Train list fragment for htmx updates."
  [trains]
  (html
   [:div.train-list
    (for [train trains]
      [:div.train-card {:onclick (str "location.href='/train/" (:train/id train) "'")}
       [:div.train-header
        [:h4.train-name (:train/name train)]
        (c/badge (name (:train/status train))
                {:variant (case (:train/status train)
                           :merged :success
                           :merging :info
                           :failed :error
                           :reviewing :warning
                           :neutral)})]
       [:div.train-stats
        [:div.stat
         [:span.stat-label "PRs"]
         [:span.stat-value (count (:train/prs train))]]
        [:div.stat
         [:span.stat-label "Ready"]
         [:span.stat-value (count (:train/ready-to-merge train))]]
        [:div.stat
         [:span.stat-label "Blocked"]
         [:span.stat-value (count (:train/blocking-prs train))]]]
       [:div.train-progress
        (c/progress-bar
         (int (* 100 (/ (count (filter #(= :merged (:pr/status %)) (:train/prs train)))
                       (max 1 (count (:train/prs train))))))
         {:variant (if (seq (:train/blocking-prs train)) :error :success)})]])]))

(defn fleet-view
  "Fleet management view showing all PR trains."
  [fleet-state]
  (layout "PR Fleet"
   [:div.fleet-view
    [:div.fleet-header
     [:div.fleet-summary
      [:h2 "PR Train Fleet"]
      [:div.summary-stats
       [:span (str (get-in fleet-state [:summary :active-trains]) " active trains")]
       [:span " • "]
       [:span (str (get-in fleet-state [:summary :total-prs]) " total PRs")]
       [:span " • "]
       [:span (str (get-in fleet-state [:summary :repos]) " repos")]]]
     [:div.fleet-actions
      (c/button "Create Train" {:variant :primary})]]
    [:section#trains-section.section
     {:hx-get "/api/trains"
      :hx-trigger "refresh from:body, every 5s"
      :hx-swap "innerHTML"}
     (train-list-fragment (:trains fleet-state))]]))

;------------------------------------------------------------------------------ Layer 3
;; Train detail view

(defn train-detail-view
  "Detailed view of a single PR train."
  [train]
  (if (:error train)
    (layout "Error" [:div.error (:error train)])
    (layout (str "Train: " (:train/name train))
     [:div.train-detail
      [:div.train-detail-header
       [:div.train-info
        [:h2 (:train/name train)]
        [:p.train-description (:train/description train)]]
       [:div.train-actions
        (c/button "Pause" {:variant :ghost
                             :onclick (str "fetch('/api/train/action?train-id="
                                          (:train/id train)
                                          "&action=pause', {method: 'POST'})")})
        (c/button "Merge Next" {:variant :primary
                                  :onclick (str "fetch('/api/train/action?train-id="
                                               (:train/id train)
                                               "&action=merge-next', {method: 'POST'})")})]]
      
      [:div.train-detail-grid
       ;; PR list
       [:section.prs-section
        [:h3 "Pull Requests"]
        [:div.pr-list
         (for [pr (sort-by :pr/merge-order (:train/prs train))]
           [:div.pr-card {:class (name (:pr/status pr))}
            [:div.pr-header
             [:span.pr-number (str "#" (:pr/number pr))]
             [:span.pr-repo (:pr/repo pr)]
             (c/badge (name (:pr/status pr)))]
            [:div.pr-title (:pr/title pr)]
            [:div.pr-details
             [:span.pr-ci (str "CI: " (name (:pr/ci-status pr)))]
             (when (seq (:pr/depends-on pr))
               [:span.pr-deps (str "Depends: " (str/join ", " (:pr/depends-on pr)))])]])]]
       
       ;; Merge graph
       [:section.graph-section
        [:h3 "Merge Order"]
        [:div.merge-graph
         (for [[idx pr] (map-indexed vector (sort-by :pr/merge-order (:train/prs train)))]
           [:div.graph-node
            [:div.node-order (str (inc idx))]
            [:div.node-content
             [:span.node-number (str "#" (:pr/number pr))]
             [:span.node-status (c/status-dot (:pr/status pr))]]])]]]])))

;------------------------------------------------------------------------------ Layer 4
;; DAG Kanban view

(defn dag-kanban-view
  "DAG-based kanban board for task visualization."
  [state]
  (layout "DAG Kanban"
   [:div.kanban-view
    [:div.kanban-header
     [:h2 "Task Status by Dependency Graph"]
     [:div.kanban-filters
      [:select.filter-select
       [:option "All Repos"]
       (for [repo (set (map :repo (:tasks state)))]
         [:option repo])]]]
    [:div.kanban-board
     (for [status [:blocked :ready :running :done]]
       [:div.kanban-column {:class (name status)}
        [:div.column-header
         [:h3 (str/upper-case (name status))]
         [:span.column-count
          (count (filter #(= status (:status %)) (:tasks state)))]]
        [:div.column-content
         (for [task (filter #(= status (:status %)) (:tasks state))]
           [:div.kanban-card
            [:div.card-header
             [:span.card-id (str (:repo task) " #" (:id task))]
             (when (seq (:dependencies task))
               [:span.card-deps (str "↓ " (count (:dependencies task)))])]
            [:div.card-title (:title task)]
            [:div.card-footer
             [:a.card-link {:href (str "/train/" (:train-id task))} "View Train →"]]])]])]]))

;------------------------------------------------------------------------------ Layer 5
;; Evidence view

(defn evidence-view
  "Evidence artifacts view."
  [state]
  (layout "Evidence"
   [:div.evidence-view
    [:div.evidence-header
     [:h2 "Evidence Bundles"]
     [:p.subtitle "Audit trail for all merged PRs"]]
    [:div.evidence-list
     (for [train (:trains state)]
       [:div.evidence-item
        [:div.evidence-info
         [:h4 (:train-name train)]
         [:span.evidence-meta
          (str (:pr-count train) " PRs • "
               (if (:has-evidence train) "Evidence available" "No evidence"))]]
        (when (:has-evidence train)
          [:button.btn.btn-sm.btn-ghost
           {:onclick (str "location.href='/api/evidence/" (:evidence-bundle-id train) "'")}
           "View Bundle →"])])]]))

;------------------------------------------------------------------------------ Layer 6
;; Workflow views

(defn workflow-list-fragment
  "Workflow list fragment for htmx updates."
  [workflows]
  (html
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
                "—")]])]]]))

(defn workflow-detail-view
  "Detailed workflow view."
  [workflow]
  (if (:error workflow)
    (layout "Error" [:div.error (:error workflow)])
    (layout (str "Workflow: " (:name workflow))
     [:div.workflow-detail
      [:h2 (:name workflow)]
      [:div.workflow-info
       [:span (str "Status: " (name (:status workflow)))]
       [:span (str "Phase: " (:phase workflow))]
       [:span (str "Progress: " (:progress workflow) "%")]]
      [:div.workflow-output
       [:h3 "Output"]
       [:pre.output-text "Workflow output would appear here..."]]])))
