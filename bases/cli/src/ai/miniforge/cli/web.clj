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
  "Web-based fleet dashboard using htmx.

   Provides an elegant web UX for managing AI-generated PRs across
   multiple repositories. Uses htmx for interactive updates without
   full page reloads.

   Features:
   - Two-pane layout (repo tree + detail view)
   - PR risk analysis and scoring
   - Batch operations (approve safe PRs)
   - Real-time updates via htmx polling
   - Dark theme optimized for developers"
  (:require
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [cheshire.core :as json]
   [org.httpkit.server :as http]
   [hiccup2.core :as h]
   [hiccup.util :refer [raw-string]]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration

(def ^:dynamic *port* 8787)
(def ^:private server-atom (atom nil))

;------------------------------------------------------------------------------ Layer 1
;; Risk analysis (reused from tui.clj)

(def risk-colors
  {:low "#22c55e"      ; green-500
   :medium "#eab308"   ; yellow-500
   :high "#ef4444"})   ; red-500

(def risk-bg-colors
  {:low "rgba(34, 197, 94, 0.1)"
   :medium "rgba(234, 179, 8, 0.1)"
   :high "rgba(239, 68, 68, 0.1)"})

(defn analyze-pr-risk
  "Analyze a PR and return risk assessment."
  [{:keys [title additions deletions changedFiles]}]
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
;; GitHub integration

(defn fetch-prs-for-repo
  "Fetch PRs for a single repository."
  [repo]
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

(defn fetch-all-prs
  "Fetch PRs for all configured repos."
  [repos]
  (vec (for [repo repos
             :let [prs (fetch-prs-for-repo repo)]
             :when prs]
         {:repo repo
          :prs prs})))

(defn approve-pr!
  "Approve a PR via gh CLI."
  [repo number]
  (let [result (process/sh "gh" "pr" "review" (str number)
                           "--repo" repo "--approve")]
    {:success (zero? (:exit result))
     :message (if (zero? (:exit result))
                "PR approved successfully"
                (str "Failed: " (:err result)))}))

(defn request-changes!
  "Request changes on a PR."
  [repo number reason]
  (let [result (process/sh "gh" "pr" "review" (str number)
                           "--repo" repo
                           "--request-changes"
                           "--body" reason)]
    {:success (zero? (:exit result))
     :message (if (zero? (:exit result))
                "Changes requested"
                (str "Failed: " (:err result)))}))

(defn fetch-pr-diff
  "Fetch the diff for a PR."
  [repo number]
  (let [result (process/sh "gh" "pr" "diff" (str number) "--repo" repo)]
    (when (zero? (:exit result))
      (:out result))))

(defn fetch-pr-body
  "Fetch the PR description/body."
  [repo number]
  (let [result (process/sh "gh" "pr" "view" (str number) "--repo" repo "--json" "body,title,labels")]
    (when (zero? (:exit result))
      (try
        (json/parse-string (:out result) true)
        (catch Exception _ nil)))))

(defn generate-pr-summary
  "Generate an AI summary of a PR for quick decision making."
  [repo number]
  (let [diff (fetch-pr-diff repo number)
        pr-info (fetch-pr-body repo number)
        diff-preview (when diff (subs diff 0 (min 6000 (count diff))))
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
      {:success false :summary "Could not generate summary"})))

(defn chat-with-claude
  "Send a chat message to Claude about a PR."
  [repo number question diff-context]
  (let [prompt (str "You are reviewing PR #" number " in " repo ".\n\n"
                    "Here is the diff:\n```\n" (subs diff-context 0 (min 8000 (count diff-context))) "\n```\n\n"
                    "User question: " question "\n\n"
                    "Please provide a concise, helpful answer focused on the code changes. "
                    "Be specific and reference actual code when relevant.")
        result (process/sh "claude" "-p" prompt)]
    (if (zero? (:exit result))
      {:success true :response (str/trim (:out result))}
      {:success false :response (str "AI analysis unavailable. Error: " (:err result))})))

;------------------------------------------------------------------------------ Layer 2.5
;; Fleet Health/Status

(defn check-gh-cli-status
  "Check if gh CLI is available and authenticated."
  []
  (let [result (process/sh "gh" "auth" "status")]
    {:available (zero? (:exit result))
     :message (if (zero? (:exit result))
                "Authenticated"
                "Not authenticated")}))

(defn check-repo-status
  "Check if a repo is accessible."
  [repo]
  (let [result (process/sh "gh" "repo" "view" repo "--json" "name")]
    {:accessible (zero? (:exit result))
     :repo repo}))

(defn get-fleet-status
  "Get overall fleet health status."
  [repos]
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

(defn generate-fleet-summary
  "Generate an executive summary of the fleet PR status."
  [repos-with-prs]
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
;; HTML Components

(def css-styles
  "
  :root {
    --bg-primary: #0f172a;
    --bg-secondary: #1e293b;
    --bg-tertiary: #334155;
    --text-primary: #f1f5f9;
    --text-secondary: #94a3b8;
    --text-muted: #64748b;
    --border-color: #475569;
    --accent-blue: #3b82f6;
    --accent-cyan: #06b6d4;
    --risk-low: #22c55e;
    --risk-medium: #eab308;
    --risk-high: #ef4444;
  }

  * { box-sizing: border-box; margin: 0; padding: 0; }

  body {
    font-family: 'SF Mono', 'JetBrains Mono', 'Fira Code', monospace;
    background: var(--bg-primary);
    color: var(--text-primary);
    line-height: 1.5;
    min-height: 100vh;
  }

  .container {
    display: flex;
    flex-direction: column;
    height: 100vh;
  }

  .header {
    background: var(--bg-secondary);
    border-bottom: 1px solid var(--border-color);
    padding: 12px 20px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .header h1 {
    font-size: 18px;
    font-weight: 600;
    color: var(--accent-cyan);
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .header-stats {
    display: flex;
    gap: 20px;
    font-size: 13px;
  }

  .stat { display: flex; align-items: center; gap: 6px; }
  .stat-low { color: var(--risk-low); }
  .stat-medium { color: var(--risk-medium); }
  .stat-high { color: var(--risk-high); }

  .main-content {
    display: flex;
    flex: 1;
    overflow: hidden;
    min-height: 0;
  }

  .sidebar {
    width: 350px;
    background: var(--bg-secondary);
    border-right: 1px solid var(--border-color);
    display: flex;
    flex-direction: column;
    overflow: hidden;
    min-height: 0;
  }

  .sidebar-header {
    padding: 12px 16px;
    border-bottom: 1px solid var(--border-color);
    font-size: 12px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    color: var(--text-secondary);
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .sidebar-content {
    flex: 1;
    overflow-y: auto;
  }

  .repo-group {
    border-bottom: 1px solid var(--border-color);
  }

  .repo-header {
    padding: 12px 16px;
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    transition: background 0.15s;
    font-size: 14px;
    font-weight: 500;
  }

  .repo-header:hover {
    background: var(--bg-tertiary);
  }

  .repo-header.expanded {
    background: rgba(59, 130, 246, 0.1);
    border-left: 3px solid var(--accent-blue);
  }

  .repo-icon { color: var(--text-muted); }
  .repo-name { flex: 1; }
  .repo-count {
    background: var(--bg-tertiary);
    padding: 2px 8px;
    border-radius: 12px;
    font-size: 12px;
    color: var(--text-secondary);
  }

  .pr-list {
    background: var(--bg-primary);
  }

  .pr-item {
    padding: 10px 16px 10px 32px;
    display: flex;
    align-items: center;
    gap: 10px;
    cursor: pointer;
    transition: background 0.15s;
    border-bottom: 1px solid rgba(71, 85, 105, 0.3);
  }

  .pr-item:hover {
    background: var(--bg-tertiary);
  }

  .pr-item.selected {
    background: rgba(59, 130, 246, 0.15);
    border-left: 3px solid var(--accent-blue);
    padding-left: 29px;
  }

  .pr-risk-dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    flex-shrink: 0;
  }

  .pr-risk-low { background: var(--risk-low); }
  .pr-risk-medium { background: var(--risk-medium); }
  .pr-risk-high { background: var(--risk-high); }

  .pr-number {
    color: var(--text-muted);
    font-size: 13px;
    width: 45px;
  }

  .pr-title {
    flex: 1;
    font-size: 13px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .detail-panel {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    min-height: 0;
  }

  .detail-header {
    padding: 20px 24px;
    border-bottom: 1px solid var(--border-color);
    background: var(--bg-secondary);
  }

  .detail-title {
    font-size: 20px;
    font-weight: 600;
    margin-bottom: 8px;
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .detail-meta {
    display: flex;
    gap: 20px;
    font-size: 13px;
    color: var(--text-secondary);
  }

  .detail-content {
    flex: 1;
    overflow-y: auto;
    padding: 24px;
  }

  .section {
    margin-bottom: 24px;
  }

  .section-title {
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 1px;
    color: var(--accent-cyan);
    margin-bottom: 12px;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--border-color);
  }

  .risk-badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    border-radius: 6px;
    font-size: 13px;
    font-weight: 500;
  }

  .risk-badge.low {
    background: rgba(34, 197, 94, 0.15);
    color: var(--risk-low);
  }

  .risk-badge.medium {
    background: rgba(234, 179, 8, 0.15);
    color: var(--risk-medium);
  }

  .risk-badge.high {
    background: rgba(239, 68, 68, 0.15);
    color: var(--risk-high);
  }

  .stats-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 16px;
    margin-bottom: 16px;
  }

  .stat-card {
    background: var(--bg-secondary);
    padding: 16px;
    border-radius: 8px;
    border: 1px solid var(--border-color);
  }

  .stat-card-value {
    font-size: 24px;
    font-weight: 600;
    margin-bottom: 4px;
  }

  .stat-card-label {
    font-size: 12px;
    color: var(--text-secondary);
  }

  .actions {
    display: flex;
    gap: 12px;
    margin-top: 20px;
  }

  .btn {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 10px 16px;
    border-radius: 6px;
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    border: none;
    transition: all 0.15s;
    font-family: inherit;
  }

  .btn-primary {
    background: var(--accent-blue);
    color: white;
  }

  .btn-primary:hover {
    background: #2563eb;
  }

  .btn-success {
    background: var(--risk-low);
    color: white;
  }

  .btn-success:hover {
    background: #16a34a;
  }

  .btn-danger {
    background: var(--risk-high);
    color: white;
  }

  .btn-danger:hover {
    background: #dc2626;
  }

  .btn-secondary {
    background: var(--bg-tertiary);
    color: var(--text-primary);
    border: 1px solid var(--border-color);
  }

  .btn-secondary:hover {
    background: var(--border-color);
  }

  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    color: var(--text-muted);
    text-align: center;
    padding: 40px;
  }

  .empty-state-icon {
    font-size: 48px;
    margin-bottom: 16px;
    opacity: 0.5;
  }

  .toast {
    position: fixed;
    bottom: 20px;
    right: 20px;
    padding: 12px 20px;
    border-radius: 8px;
    font-size: 14px;
    animation: slideIn 0.3s ease;
    z-index: 1000;
  }

  .toast-success {
    background: var(--risk-low);
    color: white;
  }

  .toast-error {
    background: var(--risk-high);
    color: white;
  }

  @keyframes slideIn {
    from { transform: translateX(100%); opacity: 0; }
    to { transform: translateX(0); opacity: 1; }
  }

  .loading {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--text-muted);
  }

  .spinner {
    width: 16px;
    height: 16px;
    border: 2px solid var(--border-color);
    border-top-color: var(--accent-blue);
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  .htmx-request .loading { display: flex; }
  .htmx-request .loaded { display: none; }

  .keyboard-hints {
    padding: 8px 20px;
    background: var(--bg-secondary);
    border-top: 1px solid var(--border-color);
    font-size: 12px;
    color: var(--text-muted);
    display: flex;
    gap: 20px;
  }

  .keyboard-hints kbd {
    background: var(--bg-tertiary);
    padding: 2px 6px;
    border-radius: 4px;
    margin-right: 4px;
  }

  /* Status indicator */
  .status-indicator {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    border-radius: 6px;
    font-size: 12px;
    font-weight: 500;
  }

  .status-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    animation: pulse 2s infinite;
  }

  .status-healthy { background: rgba(34, 197, 94, 0.15); color: var(--risk-low); }
  .status-healthy .status-dot { background: var(--risk-low); }

  .status-degraded { background: rgba(234, 179, 8, 0.15); color: var(--risk-medium); }
  .status-degraded .status-dot { background: var(--risk-medium); }

  .status-error { background: rgba(239, 68, 68, 0.15); color: var(--risk-high); }
  .status-error .status-dot { background: var(--risk-high); }

  .status-warning { background: rgba(234, 179, 8, 0.15); color: var(--risk-medium); }
  .status-warning .status-dot { background: var(--risk-medium); }

  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.5; }
  }

  /* Chat panel */
  .chat-section {
    margin-top: 24px;
    border: 1px solid var(--border-color);
    border-radius: 8px;
    overflow: hidden;
  }

  .chat-header {
    background: var(--bg-tertiary);
    padding: 12px 16px;
    font-size: 13px;
    font-weight: 600;
    display: flex;
    align-items: center;
    gap: 8px;
    border-bottom: 1px solid var(--border-color);
  }

  .chat-messages {
    max-height: 300px;
    overflow-y: auto;
    padding: 16px;
    background: var(--bg-primary);
  }

  .chat-message {
    margin-bottom: 16px;
    padding: 12px;
    border-radius: 8px;
  }

  .chat-message.user {
    background: var(--accent-blue);
    color: white;
    margin-left: 40px;
  }

  .chat-message.assistant {
    background: var(--bg-secondary);
    margin-right: 40px;
    border: 1px solid var(--border-color);
  }

  .chat-message pre {
    background: var(--bg-primary);
    padding: 8px;
    border-radius: 4px;
    overflow-x: auto;
    margin-top: 8px;
    font-size: 12px;
  }

  .chat-input-container {
    display: flex;
    gap: 8px;
    padding: 12px;
    background: var(--bg-secondary);
    border-top: 1px solid var(--border-color);
  }

  .chat-input {
    flex: 1;
    background: var(--bg-primary);
    border: 1px solid var(--border-color);
    border-radius: 6px;
    padding: 10px 14px;
    color: var(--text-primary);
    font-family: inherit;
    font-size: 13px;
  }

  .chat-input:focus {
    outline: none;
    border-color: var(--accent-blue);
  }

  .chat-input::placeholder {
    color: var(--text-muted);
  }

  .quick-questions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding: 12px 16px;
    background: var(--bg-secondary);
    border-top: 1px solid var(--border-color);
  }

  .quick-question {
    background: var(--bg-tertiary);
    border: 1px solid var(--border-color);
    border-radius: 16px;
    padding: 6px 12px;
    font-size: 12px;
    color: var(--text-secondary);
    cursor: pointer;
    transition: all 0.15s;
  }

  .quick-question:hover {
    background: var(--accent-blue);
    color: white;
    border-color: var(--accent-blue);
  }

  /* Fleet summary banner */
  .fleet-summary {
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    border-radius: 8px;
    padding: 16px 20px;
    margin: 16px 20px;
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .fleet-summary-icon {
    font-size: 24px;
  }

  .fleet-summary-content {
    flex: 1;
  }

  .fleet-summary-title {
    font-weight: 600;
    margin-bottom: 4px;
  }

  .fleet-summary-recommendation {
    color: var(--text-secondary);
    font-size: 14px;
  }

  .fleet-summary-actions {
    display: flex;
    gap: 8px;
  }

  /* AI Summary in PR detail */
  .ai-summary {
    background: linear-gradient(135deg, rgba(6, 182, 212, 0.1), rgba(59, 130, 246, 0.1));
    border: 1px solid rgba(6, 182, 212, 0.3);
    border-radius: 8px;
    padding: 16px;
    margin-top: 16px;
  }

  .ai-summary-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
    color: var(--accent-cyan);
    margin-bottom: 8px;
  }

  .ai-summary-content {
    color: var(--text-primary);
    line-height: 1.6;
    white-space: pre-wrap;
  }

  .ai-summary-loading {
    color: var(--text-muted);
    font-style: italic;
  }

  .generate-summary-btn {
    background: transparent;
    border: 1px dashed var(--border-color);
    color: var(--text-secondary);
    padding: 12px 16px;
    border-radius: 8px;
    cursor: pointer;
    width: 100%;
    text-align: center;
    margin-top: 16px;
    transition: all 0.15s;
  }

  .generate-summary-btn:hover {
    border-color: var(--accent-cyan);
    color: var(--accent-cyan);
    background: rgba(6, 182, 212, 0.05);
  }
  ")

(defn render-page
  "Render the main HTML page."
  [body]
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

(defn render-repo-tree
  "Render the repository tree sidebar."
  [repos-with-prs selected-pr]
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
            [:span.pr-title title]])]])]]))

(defn render-ai-summary
  "Render the AI-generated summary section."
  [summary]
  (h/html
   [:div.ai-summary
    [:div.ai-summary-header
     [:span "🤖"]
     [:span "AI Analysis"]]
    [:div.ai-summary-content (:summary summary)]]))

(defn render-ai-summary-placeholder
  "Render placeholder to generate AI summary with auto-trigger."
  [repo number]
  (h/html
   [:div
    {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/summary")
     :hx-trigger "load"
     :hx-target "this"
     :hx-swap "outerHTML"}
    [:div {:style "color: var(--text-muted); font-style: italic; padding: 12px;"}
     "🤖 Generating AI summary..."]]))

(defn render-pr-detail
  "Render the PR detail panel."
  [{:keys [number title author url repo additions deletions analysis]}]
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

(defn render-chat-message
  "Render a chat message exchange."
  [question response]
  (h/html
   [:div
    [:div.chat-message.user question]
    [:div.chat-message.assistant
     [:pre {:style "white-space: pre-wrap; word-wrap: break-word;"} response]]]))

(defn render-status-indicator
  "Render the fleet status indicator."
  [status]
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

(defn render-fleet-summary
  "Render the fleet summary banner with actionable recommendation."
  [summary]
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

(defn render-dashboard
  "Render the full dashboard."
  [repos-with-prs selected-pr fleet-status]
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

(defn render-toast
  "Render a toast notification."
  [message success?]
  (h/html
   [:div.toast {:class (if success? "toast-success" "toast-error")
                :hx-swap-oob "true"
                :_ "on load wait 3s then remove me"}
    message]))

;------------------------------------------------------------------------------ Layer 4
;; HTTP handlers

(defn- parse-repos-from-config []
  (let [config-path (str (System/getProperty "user.home") "/.miniforge/config.edn")]
    (if (.exists (java.io.File. config-path))
      (-> (slurp config-path)
          (edn/read-string)
          (get-in [:fleet :repos] []))
      [])))

(defn handler
  "Main HTTP request handler."
  [{:keys [uri request-method] :as req}]
  (let [repos (parse-repos-from-config)]
    (cond
      ;; Main page
      (and (= uri "/") (= request-method :get))
      (let [repos-with-prs (fetch-all-prs repos)
            fleet-status (get-fleet-status repos)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (render-page (render-dashboard repos-with-prs nil fleet-status))})

      ;; Refresh data
      (and (= uri "/api/refresh") (= request-method :get))
      (let [repos-with-prs (fetch-all-prs repos)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str (render-repo-tree repos-with-prs nil)
                    (h/html [:div#detail-panel.detail-panel (render-empty-detail)]))})

      ;; Get PR detail
      (and (str/starts-with? uri "/api/pr/") (= request-method :get))
      (let [path-parts (str/split (subs uri 8) #"/")
            repo (java.net.URLDecoder/decode (first path-parts) "UTF-8")
            number (Integer/parseInt (second path-parts))
            prs (fetch-prs-for-repo repo)
            pr (first (filter #(= (:number %) number) prs))]
        (if pr
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (str (render-pr-detail pr))}
          {:status 404
           :body "PR not found"}))

      ;; Approve PR
      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/approve") (= request-method :post))
      (let [path-parts (str/split (subs uri 8) #"/")
            repo (java.net.URLDecoder/decode (first path-parts) "UTF-8")
            number (Integer/parseInt (second path-parts))
            result (approve-pr! repo number)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str (render-toast (:message result) (:success result)))})

      ;; Request changes on PR
      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/reject") (= request-method :post))
      (let [path-parts (str/split (subs uri 8) #"/")
            repo (java.net.URLDecoder/decode (first path-parts) "UTF-8")
            number (Integer/parseInt (second path-parts))
            ;; Get reason from hx-prompt header
            reason (get-in req [:headers "hx-prompt"] "Changes requested")
            result (request-changes! repo number reason)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str (render-toast (:message result) (:success result)))})

      ;; Batch approve safe PRs
      (and (= uri "/api/batch-approve") (= request-method :post))
      (let [repos-with-prs (fetch-all-prs repos)
            safe-prs (filter #(= :low (get-in % [:analysis :risk]))
                             (mapcat :prs repos-with-prs))
            results (for [{:keys [repo number]} safe-prs]
                      (approve-pr! repo number))
            success-count (count (filter :success results))]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str (render-toast (str "Approved " success-count " of " (count safe-prs) " PRs")
                                  (= success-count (count safe-prs))))})

      ;; Chat with AI about PR
      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/chat") (= request-method :post))
      (let [path-parts (str/split (subs uri 8) #"/")
            repo (java.net.URLDecoder/decode (first path-parts) "UTF-8")
            number (Integer/parseInt (second path-parts))
            ;; Parse form body - could be URL-encoded or JSON
            body-str (when-let [body (:body req)]
                       (slurp body))
            ;; Try to parse as JSON first (from hx-vals), then URL-encoded
            question (cond
                       ;; JSON body from hx-vals
                       (and body-str (str/starts-with? (str/trim body-str) "{"))
                       (get (json/parse-string body-str) "question" "What are the key changes?")

                       ;; URL-encoded form data
                       (and body-str (str/includes? body-str "="))
                       (let [params (into {} (for [pair (str/split body-str #"&")
                                                   :let [[k v] (str/split pair #"=" 2)]
                                                   :when (and k v)]
                                               [(keyword k) (java.net.URLDecoder/decode v "UTF-8")]))]
                         (or (:question params) "What are the key changes?"))

                       :else "What are the key changes in this PR?")
            _ (println "Chat request for PR #" number "in" repo "- Question:" question)
            diff (fetch-pr-diff repo number)]
        (if diff
          (let [result (chat-with-claude repo number question diff)]
            {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (str (render-chat-message question (:response result)))})
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (str (render-chat-message question "Could not fetch PR diff. Make sure you have access to this repository."))}))

      ;; Get fleet status
      (and (= uri "/api/status") (= request-method :get))
      (let [fleet-status (get-fleet-status repos)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str (render-status-indicator fleet-status))})

      ;; Generate AI summary for PR
      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/summary") (= request-method :post))
      (let [path-parts (str/split (subs uri 8) #"/")
            repo (java.net.URLDecoder/decode (first path-parts) "UTF-8")
            number (Integer/parseInt (second path-parts))
            _ (println "Generating AI summary for PR #" number "in" repo)
            result (generate-pr-summary repo number)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str (if (:success result)
                      (render-ai-summary result)
                      (h/html [:div.ai-summary
                               [:div.ai-summary-header [:span "⚠️"] [:span "Summary Unavailable"]]
                               [:div.ai-summary-content {:style "color: var(--text-muted)"}
                                (:summary result)]])))})

      ;; 404
      :else
      {:status 404
       :body "Not found"})))

;------------------------------------------------------------------------------ Layer 5
;; Server lifecycle

(defn stop-server!
  "Stop the web server."
  []
  (when-let [server @server-atom]
    (server)
    (reset! server-atom nil)
    (println "Server stopped.")))

(defn start-server!
  "Start the web server."
  [& {:keys [port] :or {port *port*}}]
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
