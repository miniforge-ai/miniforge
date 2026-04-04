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

(ns ai.miniforge.cli.web.components
  "HTML components for dashboard."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup2.core :as h]
   [hiccup.util :refer [raw-string]]
   [ai.miniforge.cli.web.risk :as risk]
   [ai.miniforge.cli.web.fleet :as fleet]))

(def css-styles
  (slurp (io/file "bases/cli/resources/dashboard.css")))

(defn page [body]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title "Miniforge Fleet Dashboard"]
      [:script {:src "https://unpkg.com/htmx.org@1.9.10"}]
      [:style (raw-string css-styles)]]
     [:body
      body
      [:script (raw-string "
        document.addEventListener('keydown', function(e) {
          if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
          switch(e.key) {
            case 'r': htmx.trigger(document.body, 'refresh'); break;
            case 'j':
              const next = document.querySelector('.pr-item.selected')?.nextElementSibling;
              if (next?.classList.contains('pr-item')) next.click();
              break;
            case 'k':
              const prev = document.querySelector('.pr-item.selected')?.previousElementSibling;
              if (prev?.classList.contains('pr-item')) prev.click();
              break;
          }
        });
      ")]]])))

(defn toast [message success?]
  (h/html
   [:div.toast {:class (if success? "toast-success" "toast-error")
                :hx-swap-oob "true"
                :_ "on load wait 3s then remove me"}
    message]))

(defn chat-message [question response]
  (h/html
   [:div
    [:div.chat-message.user question]
    [:div.chat-message.assistant
     [:pre {:style "white-space: pre-wrap; word-wrap: break-word;"} response]]]))

(defn status-indicator [status]
  (let [status-class (name (:overall status))
        status-text (case (:overall status)
                      :healthy "All systems operational"
                      :degraded "Some repos unreachable"
                      :warning "No repos configured"
                      :error "GitHub CLI not authenticated"
                      "Unknown")]
    (h/html
     [:div.status-indicator {:class (str "status-" status-class)
                             :hx-get "/api/status"
                             :hx-trigger "every 60s"
                             :hx-swap "outerHTML"}
      [:span.status-dot]
      [:span status-text]])))

(defn workflow-status-icon [run]
  (let [s (:status run) c (:conclusion run)]
    (cond
      (= s "in_progress") "⏳"
      (= c "success") "✓"
      (#{"failure" "timed_out" "startup_failure"} c) "✗"
      :else "○")))

(defn workflow-status [repos]
  (let [{:keys [running failed succeeded runs]} (fleet/get-workflow-status repos)]
    (h/html
     [:div.workflow-status
      {:hx-get "/api/workflows" :hx-trigger "every 60s" :hx-swap "outerHTML"}
      [:div.workflow-status-header [:span "⚙️ Workflow Status"]]
      [:div.workflow-stats
       [:span.workflow-stat.workflow-stat-running [:span (str running " ⏳")]]
       [:span.workflow-stat.workflow-stat-failed [:span (str failed " ✗")]]
       [:span.workflow-stat.workflow-stat-passed [:span (str succeeded " ✓")]]]
      [:div.workflow-runs
       (if (seq runs)
         (for [run runs]
           [:div.workflow-run
            [:span.workflow-run-status (workflow-status-icon run)]
            [:span.workflow-run-name (:workflowName run)]
            [:span.workflow-run-time (fleet/format-time-ago (:createdAt run))]])
         [:div {:style "color: var(--text-muted); font-size: 12px; text-align: center;"}
          "No recent workflows"])]])))

(defn ai-summary [summary]
  (h/html
   [:div.ai-summary
    [:div.ai-summary-header [:span "🤖"] [:span "AI Analysis"]]
    [:div.ai-summary-content (:summary summary)]]))

(defn ai-summary-placeholder [repo number]
  (h/html
   [:div
    {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/summary")
     :hx-trigger "load" :hx-target "this" :hx-swap "outerHTML"}
    [:div {:style "color: var(--text-muted); font-style: italic; padding: 12px;"}
     "🤖 Generating AI summary..."]]))

(defn ai-summary-error [message]
  (h/html
   [:div.ai-summary
    [:div.ai-summary-header [:span "⚠️"] [:span "Summary Unavailable"]]
    [:div.ai-summary-content {:style "color: var(--text-muted)"} message]]))

(defn empty-detail []
  (h/html
   [:div.empty-state
    [:div.empty-state-icon "📋"]
    [:h3 "Select a PR to view details"]
    [:p {:style "margin-top: 8px;"}
     "Choose a pull request from the list to see AI analysis and take actions."]]))

(defn fleet-summary [summary]
  (let [{:keys [total recommendation high-risk medium-risk low-risk]} summary
        icon (cond
               (pos? (:count high-risk)) "🚨"
               (pos? (:count medium-risk)) "📋"
               (pos? (:count low-risk)) "✅"
               :else "🎉")]
    (h/html
     [:div.fleet-summary
      [:div.fleet-summary-icon icon]
      [:div.fleet-summary-content
       [:div.fleet-summary-title (str total " Open PRs Across Fleet")]
       [:div.fleet-summary-recommendation recommendation]]
      [:div.fleet-summary-actions
       (when (pos? (:count low-risk))
         [:button.btn.btn-success
          {:hx-post "/api/batch-approve"
           :hx-target "#toast-container"
           :hx-swap "innerHTML"
           :hx-confirm (str "Approve all " (:count low-risk) " low-risk PRs?")}
          (str "Approve " (:count low-risk) " Safe")])]])))

(defn pr-url [repo number]
  (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number))

(defn pr-detail [{:keys [number title author url repo additions deletions analysis]}]
  (let [{:keys [risk complexity summary suggested-action reasons total-changes file-count]} analysis]
    (h/html
     [:div
      [:div.detail-header
       [:div.detail-title
        [:span.risk-badge {:class (name risk)}
         (case risk :low "●" :medium "◐" :high "◉")
         (str/upper-case (name risk))]
        (str "PR #" number)]
       [:div.detail-meta
        [:span "📦 " repo]
        [:span "👤 " (get author :login "unknown")]
        [:span "📊 +" additions " / -" deletions]]]

      [:div.detail-content
       [:div.section
        [:div.section-title "Title"]
        [:p title]]

       [:div.section
        [:div.section-title "AI Analysis"]
        [:div.stats-grid
         [:div.stat-card
          [:div.stat-card-value {:style (str "color: " (get risk/colors risk))}
           (str/upper-case (name risk))]
          [:div.stat-card-label "Risk Level"]]
         [:div.stat-card
          [:div.stat-card-value (str/capitalize (name complexity))]
          [:div.stat-card-label "Complexity"]]
         [:div.stat-card
          [:div.stat-card-value total-changes]
          [:div.stat-card-label "Lines Changed"]]
         [:div.stat-card
          [:div.stat-card-value file-count]
          [:div.stat-card-label "Files Modified"]]]

        [:div {:style "margin-top: 16px;"} [:strong "Summary: "] summary]

        (when (seq reasons)
          [:div {:style "margin-top: 8px; color: var(--text-secondary);"}
           [:strong "Factors: "] (str/join ", " reasons)])

        [:div {:style (str "margin-top: 16px; padding: 12px; border-radius: 6px; background: " (get risk/bg-colors risk))}
         [:strong "Recommendation: "] suggested-action]

        [:div#ai-summary-container (ai-summary-placeholder repo number)]]

       [:div.section
        [:div.section-title "Actions"]
        [:div.actions
         [:button.btn.btn-success
          {:hx-post (str (pr-url repo number) "/approve")
           :hx-target "#toast-container" :hx-swap "innerHTML"}
          "✓ Approve"]
         [:button.btn.btn-danger
          {:hx-post (str (pr-url repo number) "/reject")
           :hx-target "#toast-container" :hx-swap "innerHTML"
           :hx-prompt "Reason for requesting changes:"}
          "✗ Request Changes"]
         [:a.btn.btn-secondary {:href url :target "_blank"} "Open in GitHub"]]]

       [:div.chat-section
        [:div.chat-header [:span "💬"] [:span "Ask AI about this PR"]]
        [:div#chat-messages.chat-messages
         [:div {:style "color: var(--text-muted); font-size: 13px;"}
          "Ask questions about this PR to get AI-powered insights."]]
        [:div.quick-questions
         (for [[label q] [["What could break?" "What could break with these changes?"]
                          ["Security concerns?" "Are there any security concerns?"]
                          ["Summarize changes" "Summarize the key changes in this PR."]
                          ["Test suggestions" "What tests should be added for these changes?"]]]
           [:button.quick-question
            {:hx-post (str (pr-url repo number) "/chat")
             :hx-target "#chat-messages" :hx-swap "beforeend"
             :hx-vals (str "{\"question\": \"" q "\"}")}
            label])]
        [:form.chat-input-container
         {:hx-post (str (pr-url repo number) "/chat")
          :hx-target "#chat-messages" :hx-swap "beforeend"}
         [:input.chat-input {:type "text" :name "question"
                             :placeholder "Ask a question about this PR..."
                             :autocomplete "off"}]
         [:button.btn.btn-primary {:type "submit"} "Ask"]]]]])))

(defn repo-tree [repos-with-prs selected-pr]
  (h/html
   [:div.sidebar
    [:div.sidebar-header
     [:span "Repositories"]
     [:button.btn.btn-secondary
      {:hx-get "/api/refresh" :hx-target "#main-content" :hx-swap "innerHTML"
       :style "padding: 4px 8px; font-size: 11px;"}
      "Refresh"]]
    [:div.sidebar-content
     (for [{:keys [repo prs]} repos-with-prs]
       [:div.repo-group
        [:div.repo-header.expanded
         [:span.repo-icon "📦"]
         [:span.repo-name repo]
         [:span.repo-count (count prs)]]
        [:div.pr-list
         (for [{:keys [number title analysis]} prs
               :let [selected? (and selected-pr
                                    (= (:repo selected-pr) repo)
                                    (= (:number selected-pr) number))]]
           [:div.pr-item
            {:class (when selected? "selected")
             :hx-get (pr-url repo number) :hx-target "#detail-panel" :hx-swap "innerHTML"}
            [:span.pr-risk-dot {:class (str "pr-risk-" (name (:risk analysis)))}]
            [:span.pr-number (str "#" number)]
            [:span.pr-title title]])]])
     (workflow-status (map :repo repos-with-prs))]]))

(defn dashboard [repos-with-prs selected-pr fleet-status]
  (let [all-prs (mapcat :prs repos-with-prs)
        pr-counts {:total (count all-prs)
                   :low (count (filter #(= :low (get-in % [:analysis :risk])) all-prs))
                   :medium (count (filter #(= :medium (get-in % [:analysis :risk])) all-prs))
                   :high (count (filter #(= :high (get-in % [:analysis :risk])) all-prs))}
        summary (fleet/generate-summary repos-with-prs)]
    (h/html
     [:div.container
      [:div.header
       [:div {:style "display: flex; align-items: center; gap: 16px;"}
        [:h1 "⚡ Fleet Dashboard"]
        (status-indicator fleet-status)]
       [:div.header-stats
        [:span.stat (str (:total pr-counts) " PRs")]
        [:span.stat.stat-low "● " (:low pr-counts) " safe"]
        [:span.stat.stat-medium "◐ " (:medium pr-counts) " review"]
        [:span.stat.stat-high "◉ " (:high pr-counts) " risky"]
        (when (pos? (:low pr-counts))
          [:button.btn.btn-success
           {:hx-post "/api/batch-approve"
            :hx-target "#toast-container" :hx-swap "innerHTML"
            :hx-confirm (str "Approve all " (:low pr-counts) " low-risk PRs?")
            :style "padding: 6px 12px;"}
           "Batch Approve Safe"])]]

      (when (pos? (:total summary)) (fleet-summary summary))

      [:div#main-content.main-content
       (repo-tree repos-with-prs selected-pr)
       [:div#detail-panel.detail-panel
        (if selected-pr (pr-detail selected-pr) (empty-detail))]]

      [:div.keyboard-hints
       [:span [:kbd "j"] "/" [:kbd "k"] " navigate"]
       [:span [:kbd "r"] " refresh"]
       [:span [:kbd "a"] " approve"]
       [:span [:kbd "x"] " reject"]]

      [:div#toast-container]])))
