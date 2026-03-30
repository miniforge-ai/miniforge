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
   [ai.miniforge.web-dashboard.components :as c]
   [ai.miniforge.web-dashboard.messages :as messages])
  (:import
   [java.text SimpleDateFormat]))

;------------------------------------------------------------------------------ Layer 0
;; Fleet fragments

(defn readiness-state-label
  "Maps readiness state keywords to human-readable labels."
  [state]
  (case state
    :merge-ready (messages/t :readiness/merge-ready)
    :ci-failing (messages/t :readiness/ci-failing)
    :changes-requested (messages/t :readiness/changes-requested)
    :merge-conflicts (messages/t :readiness/merge-conflicts)
    :policy-failing (messages/t :readiness/policy-failing)
    :dep-blocked (messages/t :readiness/dep-blocked)
    :needs-review (messages/t :readiness/needs-review)
    (if state (name state) (messages/t :readiness/unknown))))

(defn readiness-state-variant
  "Maps readiness state keywords to badge variant keywords."
  [state]
  (case state
    :merge-ready :success
    :ci-failing :error
    :changes-requested :warning
    :merge-conflicts :error
    :policy-failing :error
    :dep-blocked :neutral
    :needs-review :info
    :neutral))

(defn error-category-variant
  "Maps error category keywords to badge variant keywords."
  [cat]
  (case cat
    (:auth :auth-failure :access :not-found) :error
    (:rate-limit :rate-limited :parse :parse-error :network :network-error) :warning
    :error))

(defn error-category-label
  "Maps error category keywords to human-readable labels."
  [cat]
  (case cat
    :auth (messages/t :error-category/auth)
    :auth-failure (messages/t :error-category/auth)
    :access (messages/t :error-category/access)
    :not-found (messages/t :error-category/not-found)
    :rate-limit (messages/t :error-category/rate-limit)
    :rate-limited (messages/t :error-category/rate-limit)
    :parse (messages/t :error-category/parse)
    :parse-error (messages/t :error-category/parse)
    :network (messages/t :error-category/network)
    :network-error (messages/t :error-category/network)
    (if cat (name cat) (messages/t :error-category/fallback))))

(defn sync-failure-entry
  "Renders a single sync failure entry with optional error-category badge."
  [{:keys [repo error action error-category]}]
  [:div.sync-failure
   [:span.sync-repo repo]
   [:span.sync-error error]
   (when (and action (not (str/blank? action)))
     [:span.sync-action action])
   (when error-category
     (c/badge (error-category-label error-category)
              {:variant (error-category-variant error-category)}))])

(defn blocking-reason-line
  "Renders a blocking reason with type-appropriate variant."
  [{:keys [blocker/type blocker/message]}]
  (let [variant (case type
                  :dependency :neutral
                  :ci :error
                  :review :warning
                  :policy :error
                  :conflict :error
                  :neutral)]
    [:div.blocking-reason
     (c/badge (name type) {:variant variant})
     [:span.blocker-message (or message (messages/t :blocker/fallback-message))]]))

(defn pr-readiness-fragment
  "Renders a readiness state label with optional score."
  [{:keys [readiness/state readiness/score]}]
  [:div.pr-readiness
   (c/badge (readiness-state-label state)
            {:variant (readiness-state-variant state)})
   (when (some? score)
     [:span.readiness-score (format "%.2f" (double score))])])

(defn fleet-action-onclick
  [action]
  (case action
    :add-repo "window.miniforge.fleet.addRepo()"
    :discover-repos "window.miniforge.fleet.discoverRepos()"
    :sync-prs "window.miniforge.fleet.syncPrs()"
    :discover-sync "window.miniforge.fleet.discoverAndSync()"
    ""))

(defn format-sync-time
  [ts]
  (when ts
    (try
      (.format (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss") ts)
      (catch Exception _ nil))))

(defn sync-status-fragment
  [last-sync]
  (if-not last-sync
    [:div.fleet-sync-status
     [:span.sync-label "Last Sync"]
     [:span.sync-message "No sync run yet."]]
    (let [status (:status last-sync)
          variant (case status
                    :success :success
                    :partial :warning
                    :failed :error
                    :neutral)
          synced (:synced last-sync 0)
          failed (:failed last-sync 0)
          ts (format-sync-time (:timestamp last-sync))]
      [:div.fleet-sync-status
       [:div.sync-topline
        [:span.sync-label "Last Sync"]
        (c/badge (name status) {:variant variant})
        (when ts
          [:span.sync-time ts])
        [:span.sync-counts (str "synced " synced ", failed " failed)]]
       (when-let [message (:message last-sync)]
         [:div.sync-message message])
       (when (seq (:failures last-sync))
         [:div.sync-failures
          (for [{:keys [repo error action]} (:failures last-sync)]
            [:div.sync-failure
             [:span.sync-repo repo]
             [:span.sync-error error]
             [:span.sync-action action]])])])))

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
                                    :onclick (fleet-action-onclick :discover-sync)})]]
     [:div.train-table
      [:table.fleet-table
       [:thead
        [:tr
         [:th "Train Name"]
         [:th (messages/t :table/status-header)]
         [:th "PRs"]
         [:th "Ready"]
         [:th "Blocked"]
         [:th "Progress"]]]
       [:tbody
        (for [train trains]
          (let [readiness-summary (:train/readiness-summary train)
                blocked-details (:train/blocking-details train)]
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
             [:td.train-stat
              [:div (count (:train/ready-to-merge train))]
              [:div.train-substat
               (str (get readiness-summary :merge-ready 0) " merge-ready")]]
             [:td.train-stat
              [:div (count (:train/blocking-prs train))]
              (when (seq blocked-details)
                [:div.train-substat
                 (for [{:keys [pr/number blocking/reasons]} (take 2 blocked-details)]
                   [:div.blocking-line
                    (str "#" number ": " (or (first reasons) "Blocked"))])])]
             [:td.train-progress
              (c/progress-bar
               (int (* 100 (/ (count (filter #(= :merged (:pr/status %)) (:train/prs train)))
                             (max 1 (count (:train/prs train))))))
               {:variant (if (seq (:train/blocking-prs train)) :error :success)})]]))]]])))

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
                           :onclick (fleet-action-onclick :add-repo)
                           :title "Add a repository to fleet configuration"})
       (c/button "Discover Repos" {:variant :secondary
                                    :onclick (fleet-action-onclick :discover-repos)
                                    :title "Discover repositories via GitHub CLI"})
       (c/button "Sync PRs" {:variant :secondary
                              :onclick (fleet-action-onclick :sync-prs)
                              :title "Import open provider PRs into PR trains"})
       (c/button "Review All PRs" {:variant :ghost
                                    :onclick "alert('PR review: Kick off review workflows for all outstanding PRs')"
                                    :title "Run automated PR review workflows"})]]
     (sync-status-fragment (:last-sync fleet-state))
     [:div.pane-filter-toolbar
      [:div#filter-chips.filter-chips]
      [:div.filter-actions
       [:button.btn.btn-sm.btn-ghost.filter-add
        {:hx-get "/api/filter-fields?scope=local&pane=fleet"
         :hx-target "#filter-modal-container"
         :hx-swap "innerHTML"
         :title "Add pane-local filter"}
        (messages/t :action/filter)]]]]
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
    (layout (messages/t :status/error) [:div.error (:error train)])
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
             (when-let [readiness (:pr/readiness pr)]
               [:span.pr-readiness
                (str "Readiness: "
                     (readiness-state-label (:readiness/state readiness))
                     " ("
                     (format "%.2f" (double (:readiness/score readiness)))
                     ")")])
             (when (seq (:pr/depends-on pr))
               [:span.pr-deps (str "Depends: " (str/join ", " (:pr/depends-on pr)))])
             (when (seq (:pr/blocking-reasons pr))
               [:span.pr-blockers
                (str "Blockers: " (str/join " | " (:pr/blocking-reasons pr)))])]])]]

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
