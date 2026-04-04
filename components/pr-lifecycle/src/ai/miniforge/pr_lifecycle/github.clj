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

(ns ai.miniforge.pr-lifecycle.github
  "GitHub API operations for PR conversation management.

   Provides functions to interact with GitHub's GraphQL and REST APIs
   for resolving conversation threads and posting replies to comments."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; GitHub CLI helpers

(defn run-gh-command
  "Run a gh CLI command and return result.

   Arguments:
   - args: Command arguments as vector
   - worktree-path: Path to git worktree

   Returns DAG result with :output or :error"
  [args worktree-path]
  (try
    (let [result (apply process/shell
                        {:dir (str worktree-path)
                         :out :string
                         :err :string
                         :continue true}
                        args)]
      (if (zero? (:exit result))
        (dag/ok {:output (str/trim (:out result ""))})
        (dag/err :gh-command-failed
                 (str/trim (:err result ""))
                 {:exit-code (:exit result)
                  :command args})))
    (catch Exception e
      (dag/err :gh-exception (.getMessage e)))))

(defn graphql-query
  "Execute a GraphQL query via gh CLI.

   Arguments:
   - query: GraphQL query string
   - worktree-path: Path to git worktree

   Options:
   - :variables - Map of GraphQL variables

   Returns DAG result with parsed JSON response"
  [query worktree-path & {:keys [variables]}]
  (let [args (cond-> ["gh" "api" "graphql" "-f" (str "query=" query)]
               variables
               (into (mapcat (fn [[k v]]
                               ["-F" (str (name k) "=" v)])
                             variables)))
        result (run-gh-command args worktree-path)]
    (if (dag/ok? result)
      (try
        (let [parsed (json/parse-string (:output (:data result)) true)]
          (if (:errors parsed)
            (dag/err :graphql-error
                     (str "GraphQL errors: " (pr-str (:errors parsed)))
                     {:errors (:errors parsed)})
            (dag/ok {:data (:data parsed)})))
        (catch Exception e
          (dag/err :json-parse-error (.getMessage e))))
      result)))

(defn graphql-mutation
  "Execute a GraphQL mutation via gh CLI.

   Arguments:
   - mutation: GraphQL mutation string
   - worktree-path: Path to git worktree

   Options:
   - :variables - Map of GraphQL variables

   Returns DAG result with parsed JSON response"
  [mutation worktree-path & {:keys [variables]}]
  (graphql-query mutation worktree-path :variables variables))

;------------------------------------------------------------------------------ Layer 1
;; GitHub API operations

(defn get-thread-id
  "Get GraphQL thread ID from a comment ID.

   GitHub review comments have both a REST API comment ID and a
   GraphQL thread ID. This function fetches the thread ID needed
   for resolving conversations.

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: Pull request number
   - comment-id: REST API comment ID (integer)

   Returns DAG result with :thread-id or error"
  [worktree-path pr-number comment-id]
  (let [;; First get repo owner/name from git remote
        remote-result (run-gh-command ["git" "config" "--get" "remote.origin.url"] worktree-path)]
    (if (dag/err? remote-result)
      remote-result
      (let [remote-url (str/trim (:output (:data remote-result)))
            ;; Parse owner/repo from URL (supports both SSH and HTTPS)
            ;; git@github.com:owner/repo.git or https://github.com/owner/repo.git
            parts (re-find #"github\.com[:/]([^/]+)/([^/.]+)" remote-url)
            owner (nth parts 1 nil)
            repo (nth parts 2 nil)]
        (if-not (and owner repo)
          (dag/err :invalid-remote
                   (str "Could not parse owner/repo from remote URL: " remote-url))
          (let [query (str "query {
  repository(owner: \"" owner "\", name: \"" repo "\") {
    pullRequest(number: " pr-number ") {
      reviewThreads(first: 100) {
        nodes {
          id
          isResolved
          comments(first: 10) {
            nodes {
              databaseId
            }
          }
        }
      }
    }
  }
}")
                result (graphql-query query worktree-path)]
            (if (dag/ok? result)
              (let [threads (get-in (:data result) [:data :repository :pullRequest :reviewThreads :nodes])
                    ;; Find thread containing our comment ID
                    matching-thread (some (fn [thread]
                                            (when (some #(= (:databaseId %) comment-id)
                                                        (get-in thread [:comments :nodes]))
                                              thread))
                                          threads)]
                (if matching-thread
                  (dag/ok {:thread-id (:id matching-thread)
                           :is-resolved (:isResolved matching-thread)})
                  (dag/err :thread-not-found
                           "Could not find thread containing comment ID"
                           {:comment-id comment-id
                            :pr-number pr-number})))
              result)))))))

(defn reply-to-comment
  "Post a reply to a review comment thread.

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: Pull request number
   - comment-id: Comment ID to reply to
   - message: Reply message text

   Returns DAG result with reply info or error"
  [worktree-path pr-number comment-id message]
  (let [;; Use gh API to post reply to PR review comment
        args ["gh" "api"
              (str "repos/{owner}/{repo}/pulls/" pr-number "/comments/" comment-id "/replies")
              "-X" "POST"
              "-f" (str "body=" message)]
        result (run-gh-command args worktree-path)]
    (if (dag/ok? result)
      (try
        (let [parsed (json/parse-string (:output (:data result)) true)]
          (dag/ok {:reply-id (:id parsed)
                   :url (:html_url parsed)
                   :body (:body parsed)}))
        (catch Exception e
          (dag/err :json-parse-error (.getMessage e))))
      result)))

(defn resolve-conversation
  "Mark a conversation thread as resolved via GraphQL.

   Arguments:
   - worktree-path: Path to git worktree
   - thread-id: GraphQL thread ID (starts with 'PRRT_' or 'RT_')

   Returns DAG result with resolution status or error"
  [worktree-path thread-id]
  (let [mutation (str "mutation {
  resolveReviewThread(input: {threadId: \"" thread-id "\"}) {
    thread {
      id
      isResolved
    }
  }
}")
        result (graphql-mutation mutation worktree-path)]
    (if (dag/ok? result)
      (let [thread (get-in (:data result) [:data :resolveReviewThread :thread])
            is-resolved (:isResolved thread)]
        (if is-resolved
          (dag/ok {:thread-id (:id thread)
                   :resolved true})
          (dag/err :resolution-failed
                   "Thread resolution returned false"
                   {:thread-id thread-id :thread thread})))
      result)))

;------------------------------------------------------------------------------ Layer 2
;; High-level conversation operations

(defn link-fix-pr-to-comment
  "Link a fix PR to a review comment and resolve the conversation.

   This is the main entry point for conversation resolution after
   creating a fix PR. It:
   1. Posts a reply linking to the fix PR
   2. Resolves the conversation thread (if configured)

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: Original PR number (where comment was made)
   - comment-id: Comment ID to reply to
   - fix-pr-number: Fix PR number to link
   - logger: Optional logger instance

   Options:
   - :auto-resolve - Whether to resolve conversation (default true)
   - :message-template - Custom message template (default: 'Fixed in PR #{fix-pr-number}')

   Returns DAG result with success status and actions taken"
  [worktree-path pr-number comment-id fix-pr-number logger
   & {:keys [auto-resolve message-template]
      :or {auto-resolve true
           message-template "Fixed in PR #{fix-pr-number}"}}]
  (when logger
    (log/info logger :pr-lifecycle :github/linking-fix-pr
              {:message "Linking fix PR to comment"
               :data {:pr-number pr-number
                      :comment-id comment-id
                      :fix-pr-number fix-pr-number}}))

  (let [;; Build reply message
        message (str/replace message-template "#{fix-pr-number}" (str "#" fix-pr-number))

        ;; Step 1: Post reply
        reply-result (reply-to-comment worktree-path pr-number comment-id message)]

    (if (dag/err? reply-result)
      ;; Reply failed - log warning but don't fail completely
      (do
        (when logger
          (log/warn logger :pr-lifecycle :github/reply-failed
                    {:message "Failed to post reply to comment"
                     :data {:error (:error reply-result)
                            :pr-number pr-number
                            :comment-id comment-id}}))
        (dag/err :reply-failed
                 (:error reply-result)
                 {:pr-number pr-number
                  :comment-id comment-id
                  :fix-pr-number fix-pr-number}))

      ;; Reply succeeded
      (do
        (when logger
          (log/info logger :pr-lifecycle :github/reply-posted
                    {:message "Posted reply to comment"
                     :data {:reply-url (:url (:data reply-result))}}))

        ;; Step 2: Resolve conversation (if enabled)
        (if-not auto-resolve
          (dag/ok {:reply-posted true
                   :resolved false
                   :reply-url (:url (:data reply-result))})

          ;; Try to resolve
          (let [;; First, get thread ID from comment ID
                thread-result (get-thread-id worktree-path pr-number comment-id)]

            (if (dag/err? thread-result)
              ;; Can't get thread ID - log warning but return success for reply
              (do
                (when logger
                  (log/warn logger :pr-lifecycle :github/thread-id-failed
                            {:message "Could not get thread ID for resolution"
                             :data {:error (:error thread-result)
                                    :comment-id comment-id}}))
                (dag/ok {:reply-posted true
                         :resolved false
                         :resolution-error (:error thread-result)
                         :reply-url (:url (:data reply-result))}))

              ;; Got thread ID - try to resolve
              (let [thread-id (:thread-id (:data thread-result))
                    already-resolved (:is-resolved (:data thread-result))]

                (if already-resolved
                  ;; Already resolved
                  (do
                    (when logger
                      (log/info logger :pr-lifecycle :github/already-resolved
                                {:message "Thread already resolved"
                                 :data {:thread-id thread-id}}))
                    (dag/ok {:reply-posted true
                             :resolved true
                             :already-resolved true
                             :thread-id thread-id
                             :reply-url (:url (:data reply-result))}))

                  ;; Try to resolve
                  (let [resolve-result (resolve-conversation worktree-path thread-id)]
                    (if (dag/ok? resolve-result)
                      (do
                        (when logger
                          (log/info logger :pr-lifecycle :github/conversation-resolved
                                    {:message "Conversation resolved successfully"
                                     :data {:thread-id thread-id
                                            :pr-number pr-number}}))
                        (dag/ok {:reply-posted true
                                 :resolved true
                                 :thread-id thread-id
                                 :reply-url (:url (:data reply-result))}))

                      ;; Resolution failed - log warning but return success for reply
                      (do
                        (when logger
                          (log/warn logger :pr-lifecycle :github/resolution-failed
                                    {:message "Failed to resolve conversation"
                                     :data {:error (:error resolve-result)
                                            :thread-id thread-id}}))
                        (dag/ok {:reply-posted true
                                 :resolved false
                                 :resolution-error (:error resolve-result)
                                 :thread-id thread-id
                                 :reply-url (:url (:data reply-result))})))))))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get thread ID from comment
  (get-thread-id "/path/to/repo" 148 2780310737)
  ; => {:success true :data {:thread-id "PRRT_..." :is-resolved false ...}}

  ;; Reply to a comment
  (reply-to-comment "/path/to/repo" 148 2780310737 "Fixed in PR #150")
  ; => {:success true :data {:reply-id ... :url "https://..." ...}}

  ;; Resolve a conversation
  (resolve-conversation "/path/to/repo" "PRRT_kwDO...")
  ; => {:success true :data {:thread-id "PRRT_..." :resolved true}}

  ;; High-level: link fix PR and resolve
  (link-fix-pr-to-comment "/path/to/repo" 148 2780310737 150 nil)
  ; => {:success true :data {:reply-posted true :resolved true :thread-id "PRRT_..." ...}}

  ;; With custom message
  (link-fix-pr-to-comment "/path/to/repo" 148 2780310737 150 nil
                          :message-template "Addressed in PR #{fix-pr-number}")

  ;; Disable auto-resolution
  (link-fix-pr-to-comment "/path/to/repo" 148 2780310737 150 nil
                          :auto-resolve false)
  ; => {:success true :data {:reply-posted true :resolved false ...}}

  :leave-this-here)
