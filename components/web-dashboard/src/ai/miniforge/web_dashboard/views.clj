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
  "Server-side rendered views for web dashboard."
  (:require
   [hiccup.page :as page]
   [clojure.string]))

;------------------------------------------------------------------------------ Layer 0
;; HTML layout and common elements

(defn- layout
  "Main page layout with htmx."
  [title & body]
  (page/html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title (str "Miniforge | " title)]
    [:link {:rel "stylesheet" :href "/css/app.css"}]
    [:script {:src "https://unpkg.com/htmx.org@2.0.0"}]]
   [:body
    [:header.app-header
     [:h1 "MINIFORGE | " title]
     [:div.status-bar
      [:span.status-label "WebSocket: "]
      [:span#ws-status.status-disconnected "Connecting..."]]]
    [:nav.app-nav
     [:a {:href "/"} "Workflows"]
     [:a {:href "/evidence"} "Evidence"]
     [:a {:href "/artifacts"} "Artifacts"]
     [:a {:href "/dag"} "DAG Kanban"]]
    [:main.app-main body]
    [:script {:type "text/javascript"}
     "
     // WebSocket connection for real-time updates
     const wsUrl = 'ws://' + window.location.host + '/ws';
     const ws = new WebSocket(wsUrl);
     const status = document.getElementById('ws-status');

     ws.onopen = () => {
       status.textContent = 'Connected';
       status.className = 'status-connected';
     };

     ws.onclose = () => {
       status.textContent = 'Disconnected';
       status.className = 'status-disconnected';
       setTimeout(() => location.reload(), 5000);
     };

     ws.onmessage = (event) => {
       const msg = JSON.parse(event.data);
       // Trigger htmx refresh for workflow list
       if (msg['event/type']?.includes('workflow') || msg.type?.includes('workflow')) {
         htmx.trigger('#workflow-list', 'refresh');
       }
     };
     "]]))

;------------------------------------------------------------------------------ Layer 1
;; Workflow list view (N5 3.2.1)

(defn- workflow-row
  "Single workflow table row."
  [{:keys [id name status phase progress agent-status] :as _workflow}]
  [:tr {:hx-get (str "/workflow/" id)
        :hx-target "#main-content"
        :hx-swap "innerHTML"
        :class "workflow-row"}
   [:td [:span {:class (str "status-icon status-" (name (or status :unknown)))}
         (case status
           :running "●"
           :success "✓"
           :failed "✗"
           :blocked "◐"
           "○")]]
   [:td [:a {:href (str "/workflow/" id)} (or name id)]]
   [:td (or phase "—")]
   [:td [:div.progress-bar
         [:div.progress-fill {:style (str "width: " (or progress 0) "%")}]]]
   [:td.agent-status (or agent-status "—")]])

(defn workflow-list
  "Workflow list view (N5 Section 3.2.1)."
  [workflows]
  (layout "Workflows"
   [:div#workflow-list
    {:hx-get "/api/workflows"
     :hx-trigger "refresh from:body, every 2s"
     :hx-swap "outerHTML"}
    [:div.view-header
     [:h2 "Active Workflows"]
     [:input.search-box
      {:type "text"
       :placeholder "Search workflows..."
       :hx-get "/api/workflows"
       :hx-trigger "keyup changed delay:300ms"
       :hx-include "[name='search']"
       :name "search"}]]
    [:table.workflow-table
     [:thead
      [:tr
       [:th "Status"]
       [:th {:hx-get "/api/workflows?sort=name"
             :hx-target "#workflow-list"} "Workflow"]
       [:th "Phase"]
       [:th "Progress"]
       [:th "Agent Status"]]]
     [:tbody
      (if (empty? workflows)
        [:tr [:td {:colspan 5} "No active workflows"]]
        (map workflow-row workflows))]]]))

;------------------------------------------------------------------------------ Layer 2
;; Workflow detail view (N5 3.2.2)

(defn- phase-item
  "Single phase in the phase list."
  [{:keys [name status] :as _phase}]
  [:li {:class (str "phase-item phase-" (clojure.core/name (or status :pending)))}
   [:span.phase-icon
    (case status
      :completed "✓"
      :running "▶"
      :failed "✗"
      "○")]
   [:span.phase-name name]])

(defn workflow-detail
  "Workflow detail view (N5 Section 3.2.2)."
  [{:keys [id name phases agent-output] :as _workflow}]
  (layout (str name " | Detail")
   [:div.workflow-detail
    [:div.detail-sidebar
     [:h3 "Phases"]
     [:ul.phase-list
      (map phase-item (or phases []))]]
    [:div.detail-main
     [:h3 "Agent Output"
      [:button.copy-btn
       {:onclick "navigator.clipboard.writeText(document.querySelector('.agent-output').textContent)"}
       "Copy"]]
     [:pre.agent-output
      {:hx-get (str "/api/workflow/" id "/output")
       :hx-trigger "every 1s"
       :hx-swap "innerHTML"}
      (or agent-output "Waiting for output...")]]]))

;------------------------------------------------------------------------------ Layer 3
;; Evidence, Artifacts, and DAG views (N5 3.2.3-3.2.5)

(defn evidence-view
  "Evidence view (N5 Section 3.2.3)."
  [evidence]
  (layout "Evidence"
   [:div.evidence-view
    [:h2 "Evidence Artifacts"]
    [:div.evidence-tree
     (for [{:keys [name children] :as _item} evidence]
       [:details
        [:summary name]
        [:ul
         (for [child children]
           [:li (:name child)])]])]]))

(defn artifacts-view
  "Artifacts view (N5 Section 3.2.4)."
  [artifacts]
  (layout "Artifacts"
   [:div.artifacts-view
    [:h2 "Artifact Browser"]
    [:table.artifact-table
     [:thead
      [:tr [:th "File"] [:th "Size"] [:th "Modified"]]]
     [:tbody
      (for [{:keys [path size modified] :as _artifact} artifacts]
        [:tr {:hx-get (str "/api/artifact?path=" path)
              :hx-target "#artifact-viewer"}
         [:td path]
         [:td size]
         [:td modified]])]]
    [:div#artifact-viewer.artifact-viewer
     [:p "Select an artifact to view"]]]))

(defn dag-kanban-view
  "DAG Kanban view (N5 Section 3.2.5)."
  [tasks]
  (layout "DAG Kanban"
   [:div.dag-kanban
    [:h2 "Task Status"]
    [:div.kanban-board
     (for [status [:blocked :ready :running :done]]
       [:div.kanban-column
        [:h3.column-header (clojure.string/upper-case (name status))]
        [:div.column-tasks
         (for [task (filter #(= (:status %) status) tasks)]
           [:div.task-card
            [:div.task-name (:name task)]
            [:div.task-deps
             (when-let [deps (:dependencies task)]
               (str "Depends on: " (clojure.string/join ", " deps)))]])]])]]))
