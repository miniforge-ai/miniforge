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

(ns ai.miniforge.cli.web.github
  "GitHub CLI operations."
  (:require
   [babashka.process :as process]
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.cli.web.risk :as risk]))

(defn sh-success? [result]
  (zero? (:exit result)))

(defn sh-error-msg [result default]
  (str/trim (or (:err result) (:out result) default)))

(defn check-auth []
  (let [result (process/sh "gh" "auth" "status")]
    {:available (sh-success? result)
     :message (if (sh-success? result) "Authenticated" "Not authenticated")}))

(defn check-claude-cli []
  (sh-success? (process/sh "which" "claude")))

(defn check-repo [repo]
  {:accessible (sh-success? (process/sh "gh" "repo" "view" repo "--json" "name"))
   :repo repo})

(defn fetch-prs [repo]
  (let [result (process/sh "gh" "pr" "list" "--repo" repo
                           "--json" "number,title,state,author,url,additions,deletions,changedFiles,createdAt,labels"
                           "--limit" "50")]
    (when (sh-success? result)
      (try
        (->> (json/parse-string (:out result) true)
             (mapv #(assoc % :repo repo :analysis (risk/analyze-pr %))))
        (catch Exception _ [])))))

(defn fetch-all-prs [repos]
  (->> repos
       (keep (fn [repo]
               (when-let [prs (fetch-prs repo)]
                 {:repo repo :prs prs})))
       vec))

(defn fetch-pr-diff [repo number]
  (let [result (process/sh "gh" "pr" "diff" (str number) "--repo" repo)]
    (when (sh-success? result) (:out result))))

(defn fetch-pr-body [repo number]
  (let [result (process/sh "gh" "pr" "view" (str number) "--repo" repo "--json" "body,title,labels")]
    (when (sh-success? result)
      (try (json/parse-string (:out result) true)
           (catch Exception _ nil)))))

(defn fetch-workflow-runs [repo]
  (let [result (process/sh "gh" "run" "list" "--repo" repo
                           "--json" "workflowName,status,conclusion,createdAt,databaseId"
                           "--limit" "10")]
    (if (sh-success? result)
      (try (json/parse-string (:out result) true)
           (catch Exception _ []))
      [])))

(defn approve-pr! [repo number]
  (let [result (process/sh "gh" "pr" "review" (str number) "--repo" repo "--approve")]
    {:success (sh-success? result)
     :message (if (sh-success? result)
                (str "✓ PR #" number " approved successfully")
                (str "❌ Failed to approve PR #" number ": "
                     (sh-error-msg result "Unknown error. Check gh CLI authentication.")))}))

(defn request-changes! [repo number reason]
  (let [result (process/sh "gh" "pr" "review" (str number)
                           "--repo" repo "--request-changes" "--body" reason)]
    {:success (sh-success? result)
     :message (if (sh-success? result)
                (str "✓ Changes requested on PR #" number)
                (str "❌ Failed to request changes: "
                     (sh-error-msg result "Unknown error. Check gh CLI authentication.")))}))

(defn generate-pr-summary [repo number]
  (if-not (check-claude-cli)
    {:success false
     :summary "Claude CLI not available. Install from https://claude.ai/download"}
    (let [diff (fetch-pr-diff repo number)
          pr-info (fetch-pr-body repo number)]
      (if-not diff
        {:success false :summary "Could not fetch PR diff."}
        (let [prompt (str "You are a senior code reviewer. Analyze this PR and provide a brief summary.\n\n"
                          "PR Title: " (:title pr-info) "\n"
                          "PR Description: " (or (:body pr-info) "(none)") "\n\n"
                          "Diff preview:\n```\n" (subs diff 0 (min 6000 (count diff))) "\n```\n\n"
                          "Provide a 2-3 sentence summary covering what it does, concerns, and recommendation.")
              result (process/sh "claude" "-p" prompt)]
          (if (sh-success? result)
            {:success true :summary (str/trim (:out result))}
            {:success false :summary (str "Claude CLI error: " (sh-error-msg result "Unknown"))}))))))

(defn chat-about-pr [repo number question diff-context]
  (if-not (check-claude-cli)
    {:success false :response "❌ Claude CLI not available."}
    (let [prompt (str "You are reviewing PR #" number " in " repo ".\n\n"
                      "Here is the diff:\n```\n" (subs diff-context 0 (min 8000 (count diff-context))) "\n```\n\n"
                      "User question: " question "\n\n"
                      "Please provide a concise, helpful answer focused on the code changes.")
          result (process/sh "claude" "-p" prompt)]
      (if (sh-success? result)
        {:success true :response (str/trim (:out result))}
        {:success false :response (str "❌ Claude CLI error: " (sh-error-msg result "Unknown"))}))))
