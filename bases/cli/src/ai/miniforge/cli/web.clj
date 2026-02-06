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

(ns ai.miniforge.cli.web
  "Fleet dashboard web server with htmx."
  (:require
   [babashka.process :as process]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [cheshire.core :as json]
   [org.httpkit.server :as http]
   [hiccup2.core :as h]
   [hiccup.util :refer [raw-string]]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0

(def ^:dynamic *port* 8787)
(def ^:private server-atom (atom nil))
(def ^:private workflow-streams (atom {}))

(defn- html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str body)})

(defn- not-found [msg]
  {:status 404 :body msg})

(defn- bad-request [msg]
  {:status 400 :body msg})

;------------------------------------------------------------------------------ Layer 1

(def risk-colors
  {:low "#22c55e"      ; green-500
   :medium "#eab308"   ; yellow-500
   :high "#ef4444"})   ; red-500

(def risk-bg-colors
  {:low "rgba(34, 197, 94, 0.1)"
   :medium "rgba(234, 179, 8, 0.1)"
   :high "rgba(239, 68, 68, 0.1)"})

(defn analyze-pr-risk [{:keys [title additions deletions changedFiles]}]
  (let [total-changes (+ (or additions 0) (or deletions 0))
        file-count (or changedFiles 0)
        title-lower (str/lower-case (or title ""))
        is-deps? (or (str/includes? title-lower "bump")
                     (str/includes? title-lower "deps")
                     (str/includes? title-lower "dependency"))
        is-docs? (or (str/includes? title-lower "readme")
                     (str/includes? title-lower "docs")
                     (str/includes? title-lower "documentation"))
        is-fix? (str/includes? title-lower "fix")
        is-refactor? (str/includes? title-lower "refactor")
        is-feature? (or (str/includes? title-lower "add")
                        (str/includes? title-lower "feat")
                        (str/includes? title-lower "implement"))

        risk (cond
               (and is-docs? (< total-changes 100)) :low
               (and is-deps? (< file-count 3)) :low
               (and (< total-changes 50) (< file-count 3)) :low
               (> total-changes 500) :high
               (> file-count 20) :high
               (and is-refactor? (> total-changes 200)) :high
               :else :medium)

        complexity (cond
                     (< total-changes 20) :trivial
                     (< total-changes 100) :simple
                     (< total-changes 300) :moderate
                     :else :complex)

        summary (cond
                  is-docs? "Documentation update"
                  is-deps? "Dependency version bump"
                  is-fix? "Bug fix"
                  is-refactor? "Code refactoring"
                  is-feature? "New feature"
                  :else "Code changes")

        suggested-action (case risk
                           :low "Safe to merge"
                           :medium "Review recommended"
                           :high "Careful review needed")

        reasons (cond-> []
                  (> total-changes 300) (conj (str total-changes " lines changed"))
                  (> file-count 10) (conj (str file-count " files modified"))
                  is-refactor? (conj "Refactoring changes"))]

    {:risk risk
     :complexity complexity
     :summary summary
     :suggested-action suggested-action
     :reasons reasons
     :total-changes total-changes
     :file-count file-count}))

;------------------------------------------------------------------------------ Layer 2

(defn fetch-prs-for-repo [repo]
  (let [result (process/sh "gh" "pr" "list" "--repo" repo
                           "--json" "number,title,state,author,url,additions,deletions,changedFiles,createdAt,labels"
                           "--limit" "50")]
    (when (zero? (:exit result))
      (try
        (let [prs (json/parse-string (:out result) true)]
          (vec (for [pr prs]
                 (assoc pr
                        :repo repo
                        :analysis (analyze-pr-risk pr)))))
        (catch Exception _ [])))))

(defn fetch-all-prs [repos]
  (vec (for [repo repos
             :let [prs (fetch-prs-for-repo repo)]
             :when prs]
         {:repo repo
          :prs prs})))

(defn approve-pr! [repo number]
  (let [result (process/sh "gh" "pr" "review" (str number)
                           "--repo" repo "--approve")]
    {:success (zero? (:exit result))
     :message (if (zero? (:exit result))
                (str "✓ PR #" number " approved successfully")
                (str "❌ Failed to approve PR #" number ": " (str/trim (or (:err result) (:out result) "Unknown error. Check gh CLI authentication."))))}))

(defn request-changes! [repo number reason]
  (let [result (process/sh "gh" "pr" "review" (str number)
                           "--repo" repo
                           "--request-changes"
                           "--body" reason)]
    {:success (zero? (:exit result))
     :message (if (zero? (:exit result))
                (str "✓ Changes requested on PR #" number)
                (str "❌ Failed to request changes: " (str/trim (or (:err result) (:out result) "Unknown error. Check gh CLI authentication."))))}))

(defn fetch-pr-diff [repo number]
  (let [result (process/sh "gh" "pr" "diff" (str number) "--repo" repo)]
    (when (zero? (:exit result))
      (:out result))))

(defn fetch-pr-body [repo number]
  (let [result (process/sh "gh" "pr" "view" (str number) "--repo" repo "--json" "body,title,labels")]
    (when (zero? (:exit result))
      (try
        (json/parse-string (:out result) true)
        (catch Exception _ nil)))))

(defn check-claude-cli []
  (let [result (process/sh "which" "claude")]
    (zero? (:exit result))))

(defn generate-pr-summary [repo number]
  (if-not (check-claude-cli)
    {:success false
     :summary "Claude CLI not available. Install from https://claude.ai/download or run: brew install anthropics/claude/claude"}
    (let [diff (fetch-pr-diff repo number)
          pr-info (fetch-pr-body repo number)]
      (if-not diff
        {:success false
         :summary "Could not fetch PR diff. Ensure you have access to the repository."}
        (let [diff-preview (subs diff 0 (min 6000 (count diff)))
              prompt (str "You are a senior code reviewer. Analyze this PR and provide a brief summary for quick decision-making.\n\n"
                          "PR Title: " (:title pr-info) "\n"
                          "PR Description: " (or (:body pr-info) "(none)") "\n\n"
                          "Diff preview:\n```\n" diff-preview "\n```\n\n"
                          "Provide a 2-3 sentence summary covering:\n"
                          "1. What this PR does\n"
                          "2. Any concerns or things to watch for\n"
                          "3. Your recommendation (approve/review closely/needs discussion)\n\n"
                          "Be concise and direct.")
              result (process/sh "claude" "-p" prompt)]
          (if (zero? (:exit result))
            {:success true :summary (str/trim (:out result))}
            {:success false
             :summary (str "Claude CLI error: " (str/trim (or (:err result) (:out result) "Unknown error")))}))))))

(defn chat-with-claude [repo number question diff-context]
  (if-not (check-claude-cli)
    {:success false
     :response "❌ Claude CLI not available. Install from https://claude.ai/download or run: brew install anthropics/claude/claude"}
    (let [prompt (str "You are reviewing PR #" number " in " repo ".\n\n"
                      "Here is the diff:\n```\n" (subs diff-context 0 (min 8000 (count diff-context))) "\n```\n\n"
                      "User question: " question "\n\n"
                      "Please provide a concise, helpful answer focused on the code changes. "
                      "Be specific and reference actual code when relevant.")
          result (process/sh "claude" "-p" prompt)]
      (if (zero? (:exit result))
        {:success true :response (str/trim (:out result))}
        {:success false
         :response (str "❌ Claude CLI error: " (str/trim (or (:err result) (:out result) "Unknown error. Ensure Claude CLI is properly configured.")))}))))

;------------------------------------------------------------------------------ Layer 2.5

(defn check-gh-cli-status []
  (let [result (process/sh "gh" "auth" "status")]
    {:available (zero? (:exit result))
     :message (if (zero? (:exit result))
                "Authenticated"
                "Not authenticated")}))

(defn fetch-workflow-runs [repo]
  (let [result (process/sh "gh" "run" "list" "--repo" repo
                           "--json" "workflowName,status,conclusion,createdAt,databaseId"
                           "--limit" "10")]
    (if (zero? (:exit result))
      (try
        (json/parse-string (:out result) true)
        (catch Exception _ []))
      [])))

(defn get-workflow-status [repos]
  (let [all-runs (mapcat #(map (fn [run] (assoc run :repo %))
                               (fetch-workflow-runs %)) repos)
        running (filter #(= "in_progress" (:status %)) all-runs)
        completed (filter #(= "completed" (:status %)) all-runs)
        failed (filter #(and (= "completed" (:status %))
                            (#{"failure" "timed_out" "startup_failure"} (:conclusion %))) completed)
        succeeded (filter #(and (= "completed" (:status %))
                               (= "success" (:conclusion %))) completed)
        recent-runs (->> all-runs
                        (sort-by :createdAt)
                        reverse
                        (take 5))]
    {:total (count all-runs)
     :running (count running)
     :failed (count failed)
     :succeeded (count succeeded)
     :runs recent-runs}))

(defn format-time-ago [iso-timestamp]
  (try
    (let [then (java.time.Instant/parse iso-timestamp)
          now (java.time.Instant/now)
          seconds (.getSeconds (java.time.Duration/between then now))
          minutes (quot seconds 60)
          hours (quot minutes 60)
          days (quot hours 24)]
      (cond
        (< seconds 60) (str seconds "s ago")
        (< minutes 60) (str minutes "m ago")
        (< hours 24) (str hours "h ago")
        :else (str days "d ago")))
    (catch Exception _ "unknown")))

(defn check-repo-status [repo]
  (let [result (process/sh "gh" "repo" "view" repo "--json" "name")]
    {:accessible (zero? (:exit result))
     :repo repo}))

(defn get-fleet-status [repos]
  (let [gh-status (check-gh-cli-status)
        repo-statuses (when (:available gh-status)
                        (map check-repo-status repos))
        accessible-count (count (filter :accessible repo-statuses))
        total-repos (count repos)]
    {:gh-cli gh-status
     :repos {:total total-repos
             :accessible accessible-count
             :statuses repo-statuses}
     :overall (cond
                (not (:available gh-status)) :error
                (zero? total-repos) :warning
                (< accessible-count total-repos) :degraded
                :else :healthy)
     :last-check (java.time.Instant/now)}))

(defn generate-fleet-summary [repos-with-prs]
  (let [all-prs (mapcat :prs repos-with-prs)
        high-risk (filter #(= :high (get-in % [:analysis :risk])) all-prs)
        medium-risk (filter #(= :medium (get-in % [:analysis :risk])) all-prs)
        low-risk (filter #(= :low (get-in % [:analysis :risk])) all-prs)]
    {:total (count all-prs)
     :high-risk {:count (count high-risk)
                 :prs (take 3 high-risk)}
     :medium-risk {:count (count medium-risk)
                   :prs (take 3 medium-risk)}
     :low-risk {:count (count low-risk)
                :prs low-risk}
     :recommendation (cond
                       (pos? (count high-risk))
                       (str "⚠️ " (count high-risk) " high-risk PR(s) need careful review before any approvals")

                       (> (count medium-risk) 5)
                       (str "📋 " (count medium-risk) " PRs need review - consider batch reviewing similar ones")

                       (and (pos? (count low-risk)) (zero? (count medium-risk)) (zero? (count high-risk)))
                       (str "✅ All " (count low-risk) " PR(s) are low-risk - safe to batch approve")

                       (zero? (count all-prs))
                       "🎉 No open PRs - fleet is clean!"

                       :else
                       (str "Mixed risk levels - review " (count high-risk) " high, " (count medium-risk) " medium risk PRs"))}))

;------------------------------------------------------------------------------ Layer 3

(def css-styles
  (slurp (io/file "bases/cli/resources/dashboard.css")))

(defn render-page [body]
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
        // Keyboard shortcuts
        document.addEventListener('keydown', function(e) {
          if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

          switch(e.key) {
            case 'r':
              htmx.trigger(document.body, 'refresh');
              break;
            case 'j':
              // Navigate down
              const next = document.querySelector('.pr-item.selected')?.nextElementSibling;
              if (next?.classList.contains('pr-item')) next.click();
              break;
            case 'k':
              // Navigate up
              const prev = document.querySelector('.pr-item.selected')?.previousElementSibling;
              if (prev?.classList.contains('pr-item')) prev.click();
              break;
          }
        });
      ")]]])))

(defn render-workflow-status [repos]
  (let [status (get-workflow-status repos)
        {:keys [running failed succeeded runs]} status
        status-icon (fn [run]
                      (let [s (:status run)
                            c (:conclusion run)]
                        (cond
                          (= s "in_progress") "⏳"
                          (= c "success") "✓"
                          (#{"failure" "timed_out" "startup_failure"} c) "✗"
                          :else "○")))]
    (h/html
     [:div.workflow-status
      {:hx-get "/api/workflows"
       :hx-trigger "every 60s"
       :hx-swap "outerHTML"}
      [:div.workflow-status-header
       [:span "⚙️ Workflow Status"]]
      [:div.workflow-stats
       [:span.workflow-stat.workflow-stat-running
        [:span (str running " ⏳")]]
       [:span.workflow-stat.workflow-stat-failed
        [:span (str failed " ✗")]]
       [:span.workflow-stat.workflow-stat-passed
        [:span (str succeeded " ✓")]]]
      [:div.workflow-runs
       (if (seq runs)
         (for [run runs]
           [:div.workflow-run
            [:span.workflow-run-status (status-icon run)]
            [:span.workflow-run-name (:workflowName run)]
            [:span.workflow-run-time (format-time-ago (:createdAt run))]])
         [:div {:style "color: var(--text-muted); font-size: 12px; text-align: center;"}
          "No recent workflows"])]])))

(defn render-repo-tree [repos-with-prs selected-pr]
  (h/html
   [:div.sidebar
    [:div.sidebar-header
     [:span "Repositories"]
     [:button.btn.btn-secondary
      {:hx-get "/api/refresh"
       :hx-target "#main-content"
       :hx-swap "innerHTML"
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
             :hx-get (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number)
             :hx-target "#detail-panel"
             :hx-swap "innerHTML"}
            [:span.pr-risk-dot {:class (str "pr-risk-" (name (:risk analysis)))}]
            [:span.pr-number (str "#" number)]
            [:span.pr-title title]])]])
     ;; Workflow status widget
     (render-workflow-status (map :repo repos-with-prs))]]))

(defn render-ai-summary [summary]
  (h/html
   [:div.ai-summary
    [:div.ai-summary-header
     [:span "🤖"]
     [:span "AI Analysis"]]
    [:div.ai-summary-content (:summary summary)]]))

(defn render-ai-summary-placeholder [repo number]
  (h/html
   [:div
    {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/summary")
     :hx-trigger "load"
     :hx-target "this"
     :hx-swap "outerHTML"}
    [:div {:style "color: var(--text-muted); font-style: italic; padding: 12px;"}
     "🤖 Generating AI summary..."]]))

(defn render-pr-detail [{:keys [number title author url repo additions deletions analysis]}]
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
          [:div.stat-card-value {:style (str "color: " (get risk-colors risk))}
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

        [:div {:style "margin-top: 16px;"}
         [:strong "Summary: "] summary]

        (when (seq reasons)
          [:div {:style "margin-top: 8px; color: var(--text-secondary);"}
           [:strong "Factors: "] (str/join ", " reasons)])

        [:div {:style (str "margin-top: 16px; padding: 12px; border-radius: 6px; background: " (get risk-bg-colors risk))}
         [:strong "Recommendation: "] suggested-action]

        ;; AI Summary button
        [:div#ai-summary-container
         (render-ai-summary-placeholder repo number)]]

       [:div.section
        [:div.section-title "Actions"]
        [:div.actions
         [:button.btn.btn-success
          {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/approve")
           :hx-target "#toast-container"
           :hx-swap "innerHTML"}
          "✓ Approve"]
         [:button.btn.btn-danger
          {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/reject")
           :hx-target "#toast-container"
           :hx-swap "innerHTML"
           :hx-prompt "Reason for requesting changes:"}
          "✗ Request Changes"]
         [:a.btn.btn-secondary {:href url :target "_blank"}
          "Open in GitHub"]]]

       ;; Chat section
       [:div.chat-section
        [:div.chat-header
         [:span "💬"]
         [:span "Ask AI about this PR"]]
        [:div#chat-messages.chat-messages
         [:div {:style "color: var(--text-muted); font-size: 13px;"}
          "Ask questions about this PR to get AI-powered insights."]]
        [:div.quick-questions
         [:button.quick-question
          {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/chat")
           :hx-target "#chat-messages"
           :hx-swap "beforeend"
           :hx-vals "{\"question\": \"What could break with these changes?\"}"}
          "What could break?"]
         [:button.quick-question
          {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/chat")
           :hx-target "#chat-messages"
           :hx-swap "beforeend"
           :hx-vals "{\"question\": \"Are there any security concerns?\"}"}
          "Security concerns?"]
         [:button.quick-question
          {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/chat")
           :hx-target "#chat-messages"
           :hx-swap "beforeend"
           :hx-vals "{\"question\": \"Summarize the key changes in this PR.\"}"}
          "Summarize changes"]
         [:button.quick-question
          {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/chat")
           :hx-target "#chat-messages"
           :hx-swap "beforeend"
           :hx-vals "{\"question\": \"What tests should be added for these changes?\"}"}
          "Test suggestions"]]
        [:form.chat-input-container
         {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/chat")
          :hx-target "#chat-messages"
          :hx-swap "beforeend"}
         [:input.chat-input
          {:type "text"
           :name "question"
           :placeholder "Ask a question about this PR..."
           :autocomplete "off"}]
         [:button.btn.btn-primary {:type "submit"} "Ask"]]]]])))

(defn render-chat-message [question response]
  (h/html
   [:div
    [:div.chat-message.user question]
    [:div.chat-message.assistant
     [:pre {:style "white-space: pre-wrap; word-wrap: break-word;"} response]]]))

(defn render-status-indicator [status]
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

(defn render-fleet-summary [summary]
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

(defn render-empty-detail []
  (h/html
   [:div.empty-state
    [:div.empty-state-icon "📋"]
    [:h3 "Select a PR to view details"]
    [:p {:style "margin-top: 8px;"}
     "Choose a pull request from the list to see AI analysis and take actions."]]))

(defn render-dashboard [repos-with-prs selected-pr fleet-status]
  (let [all-prs (mapcat :prs repos-with-prs)
        pr-counts {:total (count all-prs)
                   :low (count (filter #(= :low (get-in % [:analysis :risk])) all-prs))
                   :medium (count (filter #(= :medium (get-in % [:analysis :risk])) all-prs))
                   :high (count (filter #(= :high (get-in % [:analysis :risk])) all-prs))}
        fleet-summary (generate-fleet-summary repos-with-prs)]
    (h/html
     [:div.container
      [:div.header
       [:div {:style "display: flex; align-items: center; gap: 16px;"}
        [:h1 "⚡ Fleet Dashboard"]
        (render-status-indicator fleet-status)]
       [:div.header-stats
        [:span.stat (str (:total pr-counts) " PRs")]
        [:span.stat.stat-low "● " (:low pr-counts) " safe"]
        [:span.stat.stat-medium "◐ " (:medium pr-counts) " review"]
        [:span.stat.stat-high "◉ " (:high pr-counts) " risky"]
        (when (pos? (:low pr-counts))
          [:button.btn.btn-success
           {:hx-post "/api/batch-approve"
            :hx-target "#toast-container"
            :hx-swap "innerHTML"
            :hx-confirm (str "Approve all " (:low pr-counts) " low-risk PRs?")
            :style "padding: 6px 12px;"}
           "Batch Approve Safe"])]]

      ;; Fleet summary banner with actionable recommendation
      (when (pos? (:total fleet-summary))
        (render-fleet-summary fleet-summary))

      [:div#main-content.main-content
       (render-repo-tree repos-with-prs selected-pr)
       [:div#detail-panel.detail-panel
        (if selected-pr
          (render-pr-detail selected-pr)
          (render-empty-detail))]]

      [:div.keyboard-hints
       [:span [:kbd "j"] "/" [:kbd "k"] " navigate"]
       [:span [:kbd "r"] " refresh"]
       [:span [:kbd "a"] " approve"]
       [:span [:kbd "x"] " reject"]]

      [:div#toast-container]])))

(defn render-toast [message success?]
  (h/html
   [:div.toast {:class (if success? "toast-success" "toast-error")
                :hx-swap-oob "true"
                :_ "on load wait 3s then remove me"}
    message]))

;------------------------------------------------------------------------------ Layer 4

(defn- parse-repos-from-config []
  (-> (str (System/getProperty "user.home") "/.miniforge/config.edn")
      java.io.File.
      (as-> f (when (.exists f) (slurp f)))
      (some-> edn/read-string (get-in [:fleet :repos]))
      (or [])))

(defn- parse-pr-path [uri]
  (let [path-parts (str/split (subs uri 8) #"/")]
    {:repo (java.net.URLDecoder/decode (first path-parts) "UTF-8")
     :number (Integer/parseInt (second path-parts))}))

(defn- parse-body-question [req]
  (when-let [body-str (some-> req :body slurp)]
    (cond
      (str/starts-with? (str/trim body-str) "{")
      (get (json/parse-string body-str) "question")

      (str/includes? body-str "=")
      (->> (str/split body-str #"&")
           (keep (fn [pair]
                   (let [[k v] (str/split pair #"=" 2)]
                     (when (and k v (= k "question"))
                       (java.net.URLDecoder/decode v "UTF-8")))))
           first))))

(defn- handle-index [repos]
  (->> (render-dashboard (fetch-all-prs repos) nil (get-fleet-status repos))
       render-page
       html-response))

(defn- handle-refresh [repos]
  (let [repos-with-prs (fetch-all-prs repos)]
    (->> (str (render-repo-tree repos-with-prs nil)
              (h/html [:div#detail-panel.detail-panel (render-empty-detail)]))
         html-response)))

(defn- handle-pr-detail [uri]
  (let [{:keys [repo number]} (parse-pr-path uri)
        pr (->> (fetch-prs-for-repo repo)
                (filter #(= (:number %) number))
                first)]
    (if pr
      (html-response (render-pr-detail pr))
      (not-found "PR not found"))))

(defn- handle-approve [uri]
  (let [{:keys [repo number]} (parse-pr-path uri)
        result (approve-pr! repo number)]
    (html-response (render-toast (:message result) (:success result)))))

(defn- handle-reject [uri req]
  (let [{:keys [repo number]} (parse-pr-path uri)
        reason (get-in req [:headers "hx-prompt"] "Changes requested")
        result (request-changes! repo number reason)]
    (html-response (render-toast (:message result) (:success result)))))

(defn- handle-batch-approve [repos]
  (let [safe-prs (->> (fetch-all-prs repos)
                      (mapcat :prs)
                      (filter #(= :low (get-in % [:analysis :risk]))))
        results (doall (for [{:keys [repo number]} safe-prs]
                         (approve-pr! repo number)))
        success-count (count (filter :success results))]
    (html-response (render-toast (str "Approved " success-count " of " (count safe-prs) " PRs")
                                 (= success-count (count safe-prs))))))

(defn- handle-chat [uri req]
  (let [{:keys [repo number]} (parse-pr-path uri)
        question (or (parse-body-question req) "What are the key changes in this PR?")
        diff (fetch-pr-diff repo number)
        response (if diff
                   (:response (chat-with-claude repo number question diff))
                   "Could not fetch PR diff. Make sure you have access to this repository.")]
    (html-response (render-chat-message question response))))

(defn- handle-status [repos]
  (->> (get-fleet-status repos)
       render-status-indicator
       html-response))

(defn- handle-summary [uri]
  (let [{:keys [repo number]} (parse-pr-path uri)
        result (generate-pr-summary repo number)]
    (html-response
     (if (:success result)
       (render-ai-summary result)
       (h/html [:div.ai-summary
                [:div.ai-summary-header [:span "⚠️"] [:span "Summary Unavailable"]]
                [:div.ai-summary-content {:style "color: var(--text-muted)"}
                 (:summary result)]])))))

(defn- handle-workflows [repos]
  (html-response (render-workflow-status repos)))

(defn- get-or-create-stream [workflow-id]
  (or (get @workflow-streams workflow-id)
      (let [stream (es/create-event-stream)]
        (swap! workflow-streams assoc workflow-id stream)
        stream)))

(defn- sse-on-open [workflow-id channel]
  (let [event-stream (get-or-create-stream workflow-id)
        sub-id (random-uuid)]
    (swap! workflow-streams assoc-in [workflow-id :subscribers channel] sub-id)
    (http/send! channel
                {:status 200
                 :headers {"Content-Type" "text/event-stream"
                           "Cache-Control" "no-cache"
                           "Connection" "keep-alive"
                           "Access-Control-Allow-Origin" "*"}}
                false)
    (es/subscribe! event-stream sub-id
                   (fn [event]
                     (http/send! channel
                                 (str "event: " (name (:event/type event)) "\n"
                                      "data: " (json/generate-string event) "\n\n")
                                 false)))))

(defn- sse-on-close [workflow-id channel]
  (when-let [sub-id (get-in @workflow-streams [workflow-id :subscribers channel])]
    (when-let [event-stream (get @workflow-streams workflow-id)]
      (es/unsubscribe! event-stream sub-id))
    (swap! workflow-streams update-in [workflow-id :subscribers] dissoc channel)))

(defn- handle-workflow-stream [uri req]
  (let [workflow-id-str (second (re-find #"/api/workflows/([^/]+)/stream" uri))
        workflow-id (try (java.util.UUID/fromString workflow-id-str)
                         (catch Exception _ nil))]
    (if-not workflow-id
      (bad-request "Invalid workflow ID")
      (http/as-channel req
        {:on-open (partial sse-on-open workflow-id)
         :on-close (partial sse-on-close workflow-id)}))))

(defn handler [{:keys [uri request-method] :as req}]
  (let [repos (parse-repos-from-config)]
    (cond
      (and (= uri "/") (= request-method :get))
      (handle-index repos)

      (and (= uri "/api/refresh") (= request-method :get))
      (handle-refresh repos)

      (and (= uri "/api/status") (= request-method :get))
      (handle-status repos)

      (and (= uri "/api/workflows") (= request-method :get))
      (handle-workflows repos)

      (and (= uri "/api/batch-approve") (= request-method :post))
      (handle-batch-approve repos)

      (and (re-matches #"/api/workflows/[^/]+/stream" uri) (= request-method :get))
      (handle-workflow-stream uri req)

      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/approve") (= request-method :post))
      (handle-approve uri)

      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/reject") (= request-method :post))
      (handle-reject uri req)

      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/chat") (= request-method :post))
      (handle-chat uri req)

      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/summary") (= request-method :post))
      (handle-summary uri)

      (and (str/starts-with? uri "/api/pr/") (= request-method :get))
      (handle-pr-detail uri)

      :else
      (not-found "Not found"))))

;------------------------------------------------------------------------------ Layer 4.5

(defn register-workflow-stream! [workflow-id event-stream]
  (swap! workflow-streams assoc workflow-id event-stream)
  workflow-id)

(defn unregister-workflow-stream! [workflow-id]
  (swap! workflow-streams dissoc workflow-id)
  nil)

(defn get-workflow-stream [workflow-id]
  (get @workflow-streams workflow-id))

;------------------------------------------------------------------------------ Layer 5

(defn stop-server! []
  (when-let [server @server-atom]
    (server)
    (reset! server-atom nil)
    (println "Server stopped.")))

(defn start-server! [& {:keys [port] :or {port *port*}}]
  (when @server-atom
    (stop-server!))
  (println (str "Starting fleet dashboard at http://localhost:" port))
  (reset! server-atom (http/run-server handler {:port port}))
  (println "Dashboard ready. Press Ctrl+C to stop."))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Start the server
  (start-server! :port 8787)

  ;; Stop the server
  (stop-server!)

  ;; Test PR fetching
  (fetch-prs-for-repo "miniforge-ai/miniforge")

  ;; Test risk analysis
  (analyze-pr-risk {:title "Bump dependencies" :additions 10 :deletions 5 :changedFiles 2})

  :end)
