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

(ns ai.miniforge.web-dashboard.views.fleet
  "Fleet view (PR train management) components."
  (:require
   [hiccup2.core :refer [html]]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.components :as c]))

;------------------------------------------------------------------------------ Layer 0
;; Fleet fragments

(defn train-list-fragment
  "Train list fragment for htmx updates."
  [trains]
  (html
   (if (empty? trains)
     [:div.empty-state
      [:div.empty-icon "🚂"]
      [:h3 "No PR Trains Yet"]
      [:p "Get started by running a workflow, or let Miniforge discover and coordinate your existing PRs."]
      [:div.empty-actions
       (c/button "+ Run Workflow" {:variant :primary
                                    :onclick "location.href='/workflows'"})
       (c/button "Discover + Sync" {:variant :secondary
                                    :onclick "fetch('/api/fleet/repos/discover', { method: 'POST' }).then(r => r.json()).then(res => { if (res.success) { return fetch('/api/fleet/prs/sync', { method: 'POST' }); } throw new Error(res.error || 'Discovery failed'); }).then(r => r.json()).then(syncRes => { if (syncRes.success) { alert('Discovery and PR sync completed.'); document.body.dispatchEvent(new CustomEvent('refresh')); } else { alert('Sync error: ' + (syncRes.error || 'Unable to synchronize PRs')); } }).catch(err => alert('Error: ' + err.message));"})]]
     [:div.train-table
      [:table.fleet-table
       [:thead
        [:tr
         [:th "Train Name"]
         [:th "Status"]
         [:th "PRs"]
         [:th "Ready"]
         [:th "Blocked"]
         [:th "Progress"]]]
       [:tbody
        (for [train trains]
          [:tr.train-row {:onclick (str "location.href='/train/" (:train/id train) "'")}
           [:td.train-name (:train/name train)]
           [:td (c/badge (name (:train/status train))
                        {:variant (case (:train/status train)
                                   :merged :success
                                   :merging :info
                                   :failed :error
                                   :reviewing :warning
                                   :neutral)})]
           [:td.train-stat (count (:train/prs train))]
           [:td.train-stat (count (:train/ready-to-merge train))]
           [:td.train-stat (count (:train/blocking-prs train))]
           [:td.train-progress
            (c/progress-bar
             (int (* 100 (/ (count (filter #(= :merged (:pr/status %)) (:train/prs train)))
                           (max 1 (count (:train/prs train))))))
             {:variant (if (seq (:train/blocking-prs train)) :error :success)})]])]]])))

;------------------------------------------------------------------------------ Layer 1
;; Fleet list view

(defn fleet-view
  "Fleet management view showing all PR trains."
  [layout fleet-state]
  (layout "PR Fleet"
   [:div.fleet-view
    [:div.fleet-header.aggregate-header
     [:div.fleet-title-row
      [:div.fleet-summary
       [:span.train-count (str (get-in fleet-state [:summary :active-trains] 0) " trains")]
       [:span.summary-divider "•"]
       [:span.pr-count (str (get-in fleet-state [:summary :total-prs] 0) " PRs")]
       [:span.summary-divider "•"]
       [:span.repo-count (str (get-in fleet-state [:summary :repos] 0) " repos with PRs")]
       [:span.summary-divider "•"]
       [:span.repo-count (str (get-in fleet-state [:summary :configured-repos] 0) " configured")]]
      [:div.fleet-actions
       (c/button "+ Run Workflow" {:variant :primary
                                    :onclick "location.href='/workflows'"
                                    :title "Execute a defined workflow spec"})
       (c/button "+ Repo" {:variant :secondary
                           :onclick "const repo = prompt('Repository (owner/name):'); if (repo) { fetch('/api/fleet/repos/add?repo=' + encodeURIComponent(repo), { method: 'POST' }).then(r => r.json()).then(res => { if (res.success) { alert((res['added?'] ? 'Added: ' : 'Already configured: ') + (res.repo || repo)); document.body.dispatchEvent(new CustomEvent('refresh')); } else { alert('Error: ' + (res.error || 'Unable to add repo')); } }).catch(err => alert('Error: ' + err.message)); }"
                           :title "Add a repository to fleet configuration"})
       (c/button "Discover Repos" {:variant :secondary
                                    :onclick "const owner = prompt('Owner/org (leave blank for current user):', '') || ''; const suffix = owner.trim() ? ('?owner=' + encodeURIComponent(owner.trim())) : ''; fetch('/api/fleet/repos/discover' + suffix, { method: 'POST' }).then(r => r.json()).then(res => { if (res.success) { alert('Discovered ' + (res.discovered || 0) + ' repos, added ' + (res.added || 0) + '.'); document.body.dispatchEvent(new CustomEvent('refresh')); } else { alert('Error: ' + (res.error || 'Repository discovery failed')); } }).catch(err => alert('Error: ' + err.message));"
                                    :title "Discover repositories via GitHub CLI"})
       (c/button "Sync PRs" {:variant :secondary
                              :onclick "fetch('/api/fleet/prs/sync', { method: 'POST' }).then(r => r.json()).then(res => { if (res.success) { const s = res.summary || {}; alert('Synced repos: ' + (res.synced || 0) + ', tracked PRs: ' + (s['tracked-prs'] || 0)); document.body.dispatchEvent(new CustomEvent('refresh')); } else { alert('Error: ' + (res.error || ('Sync failed for ' + (res.failed || 0) + ' repo(s)'))); document.body.dispatchEvent(new CustomEvent('refresh')); } }).catch(err => alert('Error: ' + err.message));"
                              :title "Import open provider PRs into PR trains"})
       (c/button "Review All PRs" {:variant :ghost
                                    :onclick "alert('PR review: Kick off review workflows for all outstanding PRs')"
                                    :title "Run automated PR review workflows"})]]
     [:div.pane-filter-toolbar
      [:div#filter-chips.filter-chips]
      [:div.filter-actions
       [:button.btn.btn-sm.btn-ghost.filter-add
        {:hx-get "/api/filter-fields?scope=local&pane=fleet"
         :hx-target "#filter-modal-container"
         :hx-swap "innerHTML"
         :title "Add pane-local filter"}
        "Filter"]]]]
    [:div#trains-section
     {:hx-get "/api/trains"
      :hx-trigger "refresh from:body, every 5s"
      :hx-swap "innerHTML"
      :data-filter-refresh "true"}
     (train-list-fragment (:trains fleet-state))]]))

;------------------------------------------------------------------------------ Layer 2
;; Train detail view

(defn train-detail-view
  "Detailed view of a single PR train."
  [layout train]
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
