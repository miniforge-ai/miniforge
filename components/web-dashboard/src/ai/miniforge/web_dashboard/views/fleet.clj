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
       (c/button "Coordinate My PRs" {:variant :secondary
                                       :onclick "alert('PR coordination coming soon')"})]]
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
    [:div.fleet-header
     [:div.fleet-title-row
      [:div.fleet-summary
       [:span.train-count (str (get-in fleet-state [:summary :active-trains] 0) " trains")]
       [:span.summary-divider "•"]
       [:span.pr-count (str (get-in fleet-state [:summary :total-prs] 0) " PRs")]
       [:span.summary-divider "•"]
       [:span.repo-count (str (get-in fleet-state [:summary :repos] 0) " repos")]]
      [:div.fleet-actions
       (c/button "+ Run Workflow" {:variant :primary
                                    :onclick "location.href='/workflows'"
                                    :title "Execute a defined workflow spec"})
       (c/button "Coordinate My PRs" {:variant :secondary
                                       :onclick "alert('PR coordination: Review repos → Create trains → Setup monitoring')"
                                       :title "Auto-discover PRs and create trains from DAGs"})
       (c/button "Review All PRs" {:variant :ghost
                                    :onclick "alert('PR review: Kick off review workflows for all outstanding PRs')"
                                    :title "Run automated PR review workflows"})]]]
    [:div#trains-section
     {:hx-get "/api/trains"
      :hx-trigger "refresh from:body, every 5s"
      :hx-swap "innerHTML"}
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
