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
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.web.risk :as risk]
   [ai.miniforge.cli.web.fleet :as fleet]))

(def ^:const language-tag
  "en")

(def ^:const page-title-key
  :web-ui/page-title)

(def ^:const selected-class
  "selected")

(def ^:const sidebar-refresh-style
  "padding: 4px 8px; font-size: 11px;")

(def ^:const batch-approve-style
  "padding: 6px 12px;")

(def ^:const empty-state-style
  "margin-top: 8px;")

(def ^:const ai-placeholder-style
  "color: var(--text-muted); font-style: italic; padding: 12px;")

(def ^:const no-workflows-style
  "color: var(--text-muted); font-size: 12px; text-align: center;")

(def ^:const summary-message-style
  "margin-top: 16px;")

(def ^:const factors-style
  "margin-top: 8px; color: var(--text-secondary);")

(def ^:const chat-empty-style
  "color: var(--text-muted); font-size: 13px;")

(def ^:const chat-response-style
  "white-space: pre-wrap; word-wrap: break-word;")

(def css-styles
  (slurp (io/file "bases/cli/resources/dashboard.css")))

(def ^:const keyboard-shortcuts-script
  "
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
      ")

(defn- t
  ([message-key]
   (messages/t message-key))
  ([message-key params]
   (messages/t message-key params)))

(defn page [body]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang language-tag}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title (t page-title-key)]
      [:script {:src "https://unpkg.com/htmx.org@1.9.10"}]
      [:style (raw-string css-styles)]]
     [:body
      body
      [:script (raw-string keyboard-shortcuts-script)]]])))

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
     [:pre {:style chat-response-style} response]]]))

(defn- overall-status-key
  [overall]
  (case overall
    :healthy :web-ui/status-healthy
    :degraded :web-ui/status-degraded
    :warning :web-ui/status-warning
    :error :web-ui/status-error
    :web-ui/status-unknown))

(defn status-indicator [status]
  (let [overall (get status :overall)
        status-class (name overall)
        status-text (t (overall-status-key overall))]
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
      [:div.workflow-status-header [:span (t :web-ui/workflow-status-header)]]
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
         [:div {:style no-workflows-style}
          (t :web-ui/workflow-status-none)])]])))

(defn ai-summary [summary]
  (h/html
   [:div.ai-summary
    [:div.ai-summary-header [:span "🤖"] [:span (t :web-ui/ai-analysis)]]
    [:div.ai-summary-content (:summary summary)]]))

(defn ai-summary-placeholder [repo number]
  (h/html
   [:div
    {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/summary")
     :hx-trigger "load" :hx-target "this" :hx-swap "outerHTML"}
    [:div {:style ai-placeholder-style}
     (t :web-ui/ai-summary-loading)]]))

(defn ai-summary-error [message]
  (h/html
   [:div.ai-summary
    [:div.ai-summary-header [:span "⚠️"] [:span (t :web-ui/summary-unavailable)]]
    [:div.ai-summary-content {:style "color: var(--text-muted)"} message]]))

(defn empty-detail []
  (h/html
   [:div.empty-state
    [:div.empty-state-icon "📋"]
    [:h3 (t :web-ui/empty-detail-heading)]
    [:p {:style empty-state-style}
     (t :web-ui/empty-detail-body)]]))

(defn- batch-approve-confirm
  [count]
  (t :web-ui/batch-approve-confirm {:count count}))

(defn- batch-approve-label
  [count]
  (t :web-ui/batch-approve-label {:count count}))

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
       [:div.fleet-summary-title (t :web-ui/open-prs-title {:count total})]
       [:div.fleet-summary-recommendation recommendation]]
      [:div.fleet-summary-actions
       (when (pos? (:count low-risk))
         [:button.btn.btn-success
          {:hx-post "/api/batch-approve"
           :hx-target "#toast-container"
           :hx-swap "innerHTML"
           :hx-confirm (batch-approve-confirm (:count low-risk))}
          (batch-approve-label (:count low-risk))])]])))

(defn pr-url [repo number]
  (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number))

(defn- risk-label
  [risk-level]
  (t (case risk-level
       :low :web-ui/risk-low
       :medium :web-ui/risk-medium
       :high :web-ui/risk-high
       :web-ui/risk-unknown)))

(defn- detail-header
  [risk-level number repo author additions deletions]
  (let [author-login (get author :login (t :web-ui/unknown-author))]
    [:div.detail-header
     [:div.detail-title
      [:span.risk-badge {:class (name risk-level)}
       (case risk-level :low "●" :medium "◐" :high "◉")
       (risk-label risk-level)]
      (t :web-ui/pr-number {:number number})]
     [:div.detail-meta
      [:span (t :web-ui/repo-meta {:repo repo})]
      [:span (t :web-ui/author-meta {:author author-login})]
      [:span (t :web-ui/change-meta {:additions additions :deletions deletions})]]]))

 (defn- analysis-stats
  [risk-level complexity total-changes file-count]
  [:div.stats-grid
   [:div.stat-card
    [:div.stat-card-value {:style (str "color: " (get risk/colors risk-level))}
     (risk-label risk-level)]
    [:div.stat-card-label (t :web-ui/risk-level-label)]]
   [:div.stat-card
    [:div.stat-card-value (str/capitalize (name complexity))]
    [:div.stat-card-label (t :web-ui/complexity-label)]]
   [:div.stat-card
    [:div.stat-card-value total-changes]
    [:div.stat-card-label (t :web-ui/lines-changed-label)]]
   [:div.stat-card
    [:div.stat-card-value file-count]
    [:div.stat-card-label (t :web-ui/files-modified-label)]]])

(defn- recommendation-box
  [risk-level suggested-action]
  (let [background-color (get risk/bg-colors risk-level)
        style-value (str "margin-top: 16px; padding: 12px; border-radius: 6px; background: "
                         background-color)]
    [:div {:style style-value}
     [:strong (t :web-ui/recommendation-prefix)]
     suggested-action]))

(defn- ai-analysis-section
  [repo number {:keys [risk complexity summary suggested-action reasons total-changes file-count]}]
  [:div.section
   [:div.section-title (t :web-ui/ai-analysis)]
   (analysis-stats risk complexity total-changes file-count)
   [:div {:style summary-message-style}
    [:strong (t :web-ui/summary-prefix)]
    summary]
   (when (seq reasons)
     [:div {:style factors-style}
      [:strong (t :web-ui/factors-prefix)]
      (str/join ", " reasons)])
   (recommendation-box risk suggested-action)
   [:div#ai-summary-container (ai-summary-placeholder repo number)]])

(defn- action-buttons
  [repo number url]
  [:div.actions
   [:button.btn.btn-success
    {:hx-post (str (pr-url repo number) "/approve")
     :hx-target "#toast-container"
     :hx-swap "innerHTML"}
    (t :web-ui/approve-button)]
   [:button.btn.btn-danger
    {:hx-post (str (pr-url repo number) "/reject")
     :hx-target "#toast-container"
     :hx-swap "innerHTML"
     :hx-prompt (t :web-ui/reject-prompt)}
    (t :web-ui/reject-button)]
   [:a.btn.btn-secondary {:href url :target "_blank"}
    (t :web-ui/open-github-button)]])

(defn- detail-actions
  [repo number url]
  [:div.section
   [:div.section-title (t :web-ui/actions-heading)]
   (action-buttons repo number url)])

(defn- quick-question-buttons
  [repo number]
  (for [{:keys [label prompt]} (t :web-ui/chat-quick-questions)]
    [:button.quick-question
     {:hx-post (str (pr-url repo number) "/chat")
      :hx-target "#chat-messages"
      :hx-swap "beforeend"
      :hx-vals (str "{\"question\": \"" prompt "\"}")}
     label]))

(defn- chat-section
  [repo number]
  [:div.chat-section
   [:div.chat-header
    [:span "💬"]
    [:span (t :web-ui/chat-heading)]]
   [:div#chat-messages.chat-messages
    [:div {:style chat-empty-style}
     (t :web-ui/chat-empty-state)]]
   [:div.quick-questions
    (quick-question-buttons repo number)]
   [:form.chat-input-container
    {:hx-post (str (pr-url repo number) "/chat")
     :hx-target "#chat-messages"
     :hx-swap "beforeend"}
    [:input.chat-input
     {:type "text"
      :name "question"
      :placeholder (t :web-ui/chat-placeholder)
      :autocomplete "off"}]
    [:button.btn.btn-primary {:type "submit"}
     (t :web-ui/chat-submit-button)]]])

(defn pr-detail [{:keys [number title author url repo additions deletions analysis]}]
  (let [risk-level (get analysis :risk)]
    (h/html
     [:div
      (detail-header risk-level number repo author additions deletions)
      [:div.detail-content
       [:div.section
        [:div.section-title (t :web-ui/title-heading)]
        [:p title]]
       (ai-analysis-section repo number analysis)
       (detail-actions repo number url)
       (chat-section repo number)]])))

(defn- repo-item-selected?
  [selected-pr repo number]
  (and selected-pr
       (= (:repo selected-pr) repo)
       (= (:number selected-pr) number)))

(defn- repo-pr-item
  [repo selected-pr {:keys [number title analysis]}]
  (let [selected? (repo-item-selected? selected-pr repo number)
        item-class (when selected? selected-class)]
    [:div.pr-item
     {:class item-class
      :hx-get (pr-url repo number)
      :hx-target "#detail-panel"
      :hx-swap "innerHTML"}
     [:span.pr-risk-dot {:class (str "pr-risk-" (name (:risk analysis)))}]
     [:span.pr-number (str "#" number)]
     [:span.pr-title title]]))

(defn- repo-group
  [selected-pr {:keys [repo prs]}]
  [:div.repo-group
   [:div.repo-header.expanded
    [:span.repo-icon "📦"]
    [:span.repo-name repo]
    [:span.repo-count (count prs)]]
   [:div.pr-list
    (for [pr prs]
      (repo-pr-item repo selected-pr pr))]])

(defn- sidebar-header
  []
  [:div.sidebar-header
   [:span (t :web-ui/repositories-heading)]
   [:button.btn.btn-secondary
    {:hx-get "/api/refresh"
     :hx-target "#main-content"
     :hx-swap "innerHTML"
     :style sidebar-refresh-style}
    (t :web-ui/refresh-button)]])

(defn repo-tree [repos-with-prs selected-pr]
  (h/html
   [:div.sidebar
    (sidebar-header)
    [:div.sidebar-content
     (for [repo-group-data repos-with-prs]
       (repo-group selected-pr repo-group-data))
     (workflow-status (map :repo repos-with-prs))]]))

(defn- pr-counts
  [all-prs]
  {:total (count all-prs)
   :low (count (filter #(= :low (get-in % [:analysis :risk])) all-prs))
   :medium (count (filter #(= :medium (get-in % [:analysis :risk])) all-prs))
   :high (count (filter #(= :high (get-in % [:analysis :risk])) all-prs))})

(defn- fleet-header
  [fleet-status]
  [:div.header
   [:div {:style "display: flex; align-items: center; gap: 16px;"}
    [:h1 (t :web-ui/fleet-dashboard-heading)]
    (status-indicator fleet-status)]])

(defn- stat-pill
  [class-name text]
  [:span {:class class-name} text])

(defn- batch-approve-safe-button
  [safe-count]
  [:button.btn.btn-success
   {:hx-post "/api/batch-approve"
    :hx-target "#toast-container"
    :hx-swap "innerHTML"
    :hx-confirm (batch-approve-confirm safe-count)
    :style batch-approve-style}
   (t :web-ui/batch-approve-safe)])

(defn- dashboard-stats
  [{:keys [total low medium high]}]
  [:div.header-stats
   (stat-pill "stat" (t :web-ui/pr-total {:count total}))
   (stat-pill "stat stat-low" (t :web-ui/pr-low {:count low}))
   (stat-pill "stat stat-medium" (t :web-ui/pr-medium {:count medium}))
   (stat-pill "stat stat-high" (t :web-ui/pr-high {:count high}))
   (when (pos? low)
     (batch-approve-safe-button low))])

(defn- detail-panel
  [selected-pr]
  [:div#detail-panel.detail-panel
   (if selected-pr
     (pr-detail selected-pr)
     (empty-detail))])

(defn- keyboard-hint
  [prefix-key suffix-key label]
  [:span
   [:kbd prefix-key]
   "/"
   [:kbd suffix-key]
   (str " " label)])

(defn- keyboard-hints
  []
  [:div.keyboard-hints
   (keyboard-hint "j" "k" (t :web-ui/hint-navigate))
   [:span [:kbd "r"] (str " " (t :web-ui/hint-refresh))]
   [:span [:kbd "a"] (str " " (t :web-ui/hint-approve))]
   [:span [:kbd "x"] (str " " (t :web-ui/hint-reject))]])

(defn dashboard [repos-with-prs selected-pr fleet-status]
  (let [all-prs (mapcat :prs repos-with-prs)
        counts (pr-counts all-prs)
        summary (fleet/generate-summary repos-with-prs)]
    (h/html
     [:div.container
      (fleet-header fleet-status)
      (dashboard-stats counts)
      (when (pos? (:total summary))
        (fleet-summary summary))
      [:div#main-content.main-content
       (repo-tree repos-with-prs selected-pr)
       (detail-panel selected-pr)]
      (keyboard-hints)
      [:div#toast-container]])))
