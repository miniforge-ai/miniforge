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
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; GitHub CLI helpers
(defn ^{:stratum 0} run-gh-command
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

;; Batched review posting (N13 §2.2 Standards Reviewer)
(def ^{:stratum 0} review-marker
  "Invisible HTML comment appended to every review body posted via
   `post-review!`. Lets `review-scheduler/existing-review-shas`
   recognize prior posts authored by the N13 Standards Reviewer
   (GitHub doesn't surface a stable bot identity for reviews posted
   under a personal access token, so we tag the body instead).
   Renders as nothing in PR Markdown."
  "<!-- miniforge:policy-eval -->")

(def ^{:stratum 0} ^:private review-threads-query
  "Paginated provider readback for pull-request review threads."
  "query($owner:String!,$repo:String!,$pr:Int!,$cursor:String) {
  repository(owner:$owner, name:$repo) {
    pullRequest(number:$pr) {
      reviewThreads(first:100, after:$cursor) {
        nodes { id isResolved }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}")

(def ^{:stratum 0} ^:private review-thread-roots-query
  "Paginated review-thread roots used to locate a REST review comment."
  "query($owner:String!,$repo:String!,$pr:Int!,$cursor:String) {
  repository(owner:$owner, name:$repo) {
    pullRequest(number:$pr) {
      reviewThreads(first:100, after:$cursor) {
        nodes { id isResolved comments(first:1) { nodes { databaseId } } }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}")

(defn- ^{:stratum 0} valid-review-thread-page?
  "True when GitHub returned the complete page shape needed to decide."
  [threads]
  (let [nodes (:nodes threads)
        page-info (:pageInfo threads)]
    (and (map? threads)
         (vector? nodes)
         (every? #(and (string? (:id %))
                       (boolean? (:isResolved %)))
                 nodes)
         (map? page-info)
         (boolean? (:hasNextPage page-info)))))

(defn- ^{:stratum 0} valid-next-cursor?
  "True when pagination can advance without repeating a page."
  [cursor seen-cursors]
  (and (string? cursor)
       (not (str/blank? cursor))
       (not (contains? seen-cursors cursor))))

(defn- ^{:stratum 0} run-gh-with-stdin
  "Run a `gh` command piping `stdin-body` over stdin. Returns the
   same DAG-shaped result as `run-gh-command`. Used for `gh api ...
   --input -` invocations where the JSON body is too large or too
   nested to fit `-f`/`-F` field-pair encoding."
  [args worktree-path stdin-body]
  (try
    (let [result (apply process/shell
                        {:dir (str worktree-path)
                         :in stdin-body
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

(defn- ^{:stratum 0} review-comment->github-comment
  "Translate one comment record from `compliance-scanner.comments`
   (`{:comment/path :comment/line :comment/body ...}`) to the inline-
   comment shape GitHub's create-review API expects."
  [c]
  (let [path (or (:comment/path c) (:path c))
        line (or (:comment/line c) (:line c))
        body (or (:comment/body c) (:body c))]
    {:path path
     :line line
     :side "RIGHT"
     :body body}))

(defn ^{:stratum 0} git-head-sha
  "Return the full HEAD SHA of `worktree-path`, or nil if `git
   rev-parse` fails. Used to populate `commit_id` for the create-review
   API, which 422s on inline `comments[]` payloads without it."
  [worktree-path]
  (try
    (let [r (process/shell {:dir (str worktree-path)
                            :out :string
                            :err :string
                            :continue true}
                           "git" "rev-parse" "HEAD")]
      (when (zero? (:exit r))
        (let [sha (str/trim (:out r ""))]
          (when (seq sha) sha))))
    (catch Throwable _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} valid-review-thread-root-page?
  "True when every thread exposes its integer root comment ID."
  [threads]
  (and (valid-review-thread-page? threads)
       (every? #(integer? (get-in % [:comments :nodes 0 :databaseId]))
               (:nodes threads))))

(defn ^{:stratum 1} graphql-query
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

(defn- ^{:stratum 1} repository-coordinates
  "Read the GitHub owner and repository name from the origin remote."
  [worktree-path]
  (let [result (run-gh-command
                ["git" "config" "--get" "remote.origin.url"]
                worktree-path)]
    (if (dag/err? result)
      result
      (let [remote-url (str/trim (get (:data result) :output ""))
            parts (re-find #"github\.com(?::\d+)?[:/]([^/]+)/([^/]+)/?$" remote-url)
            owner (nth parts 1 nil)
            repo (some-> (nth parts 2 nil)
                         (str/replace #"\.git$" ""))]
        (if (and owner (seq repo))
          (dag/ok {:owner owner :repo repo})
          (dag/err :invalid-remote
                   "Could not parse GitHub owner/repository from origin remote"))))))

(defn- ^{:stratum 1} review-comment-root-id
  "Resolve a review reply to its thread's root REST comment ID."
  [worktree-path owner repo comment-id]
  (let [result (run-gh-command
                ["gh" "api" (str "repos/" owner "/" repo
                                  "/pulls/comments/" comment-id)]
                worktree-path)]
    (if (dag/err? result)
      result
      (try
        (let [comment (json/parse-string (get (:data result) :output "") true)
              root-id (if-some [reply-to-id (:in_reply_to_id comment)]
                        reply-to-id
                        (:id comment))]
          (if (integer? root-id)
            (dag/ok root-id)
            (dag/err :invalid-review-comment-response
                     "GitHub returned a review comment without an integer ID")))
        (catch Exception e
          (dag/err :json-parse-error (.getMessage e)))))))

(defn ^{:stratum 1} reply-to-comment
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

(defn- ^{:stratum 1} ensure-marker
  "Append `review-marker` to `body` if not already present.
   Idempotent. Used unconditionally by `post-review!`."
  [body]
  (if (and (string? body) (str/includes? body review-marker))
    body
    (str (or body "") "\n\n" review-marker)))

;------------------------------------------------------------------------------ Layer 2

;; GitHub API operations
(defn ^{:stratum 2} get-thread-id
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
  (let [repository-result (repository-coordinates worktree-path)]
    (if (dag/err? repository-result)
      repository-result
      (let [{:keys [owner repo]} (:data repository-result)
            root-result (review-comment-root-id worktree-path owner repo comment-id)]
        (if (dag/err? root-result)
          root-result
          (loop [cursor nil
                 seen-cursors #{}]
            (let [variables (cond-> {:owner owner :repo repo :pr pr-number}
                              cursor (assoc :cursor cursor))
                  result (graphql-query review-thread-roots-query worktree-path
                                        :variables variables)]
              (if (dag/err? result)
                result
                (let [threads (get-in (:data result)
                                      [:data :repository :pullRequest :reviewThreads])
                      matching-thread
                      (some #(when (= (:data root-result)
                                      (get-in % [:comments :nodes 0 :databaseId]))
                               %)
                            (:nodes threads))]
                  (cond
                    (not (valid-review-thread-root-page? threads))
                    (dag/err :invalid-review-thread-response
                             "GitHub returned incomplete review thread state")

                    matching-thread
                    (dag/ok {:thread-id (:id matching-thread)
                             :is-resolved (:isResolved matching-thread)})

                    (:hasNextPage (:pageInfo threads))
                    (let [next-cursor (:endCursor (:pageInfo threads))]
                      (if-not (valid-next-cursor? next-cursor seen-cursors)
                        (dag/err :invalid-pagination
                                 "GitHub returned an invalid review thread cursor")
                        (recur next-cursor (conj seen-cursors next-cursor))))

                    :else
                    (dag/err :thread-not-found
                             "Could not find thread containing comment ID"
                             {:comment-id comment-id :pr-number pr-number})))))))))))

(defn ^{:stratum 2} post-review!
  "Post a single PR review batching multiple inline comments.

   Uses the create-a-review REST endpoint
   (`POST /repos/{owner}/{repo}/pulls/{pull_number}/reviews`) so all
   violations land as one expandable review on the PR rather than as
   N separate top-level comments. The review is posted with
   `event: \"COMMENT\"` — non-blocking, no approval/changes-requested
   semantics. The `{owner}/{repo}` template is resolved by `gh` from
   the worktree's git remote.

   Arguments:
   - worktree-path: path to a checkout of the PR's repo (gh resolves
                    `{owner}/{repo}` from this worktree's remote)
   - pr-number:     pull request number
   - commit-id:     full SHA of the PR's head commit. REQUIRED — the
                    create-review API returns 422 on inline
                    `comments[]` payloads without it. Callers can use
                    `(git-head-sha worktree-path)` after a
                    `gh pr checkout` to derive this.
   - body:          review summary string (markdown)
   - comments:      vector of comment records. Either the renderer
                    shape (`:comment/path`/`:comment/line`/`:comment/body`)
                    or a flatter `{:path :line :body}` shape; both
                    accepted.

   Returns DAG result with `{:review-id :url :state :comment-count}`
   on success or typed error on failure."
  [worktree-path pr-number commit-id body comments]
  (if (or (nil? commit-id) (and (string? commit-id) (str/blank? commit-id)))
    (dag/err :missing-commit-id
             "post-review! requires a non-blank commit-id (PR head SHA)"
             {:pr-number pr-number})
    (let [payload   {:body      (ensure-marker body)
                     :event     "COMMENT"
                     :commit_id commit-id
                     :comments  (mapv review-comment->github-comment comments)}
          json-body (json/generate-string payload)
          endpoint  (str "repos/{owner}/{repo}/pulls/" pr-number "/reviews")
          result    (run-gh-with-stdin
                     ["gh" "api" endpoint "-X" "POST" "--input" "-"]
                     worktree-path
                     json-body)]
      (if (dag/ok? result)
        (try
          (let [parsed (json/parse-string (:output (:data result)) true)]
            (dag/ok {:review-id (:id parsed)
                     :url       (:html_url parsed)
                     :state     (:state parsed)
                     :comment-count (count (:comments payload))}))
          (catch Exception e
            (dag/err :json-parse-error (.getMessage e))))
        result))))

(defn ^{:stratum 2} resolve-conversation
  "Mark a conversation thread as resolved via GraphQL.

   Arguments:
   - worktree-path: Path to git worktree
   - thread-id: GraphQL thread ID (starts with 'PRRT_' or 'RT_')

  Returns DAG result with resolution status or error"
  [worktree-path thread-id]
  (let [mutation "mutation($threadId:ID!) {
  resolveReviewThread(input: {threadId: $threadId}) {
    thread {
      id
      isResolved
    }
  }
}"
        result (graphql-query mutation worktree-path
                              :variables {:threadId thread-id})]
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

(defn ^{:stratum 2} unresolved-review-threads
  "Read every review-thread page and report whether any thread is unresolved."
  [worktree-path pr-number]
  (let [repository-result (repository-coordinates worktree-path)]
    (if (dag/err? repository-result)
      repository-result
      (let [{:keys [owner repo]} (:data repository-result)]
        (loop [cursor nil
               seen-cursors #{}
               unresolved-count 0]
          (let [variables (cond-> {:owner owner :repo repo :pr pr-number}
                            cursor (assoc :cursor cursor))
                result (graphql-query review-threads-query worktree-path
                                      :variables variables)]
            (if (dag/err? result)
              result
              (let [threads (get-in (:data result)
                                    [:data :repository :pullRequest :reviewThreads])
                    page-info (:pageInfo threads)]
                (if-not (valid-review-thread-page? threads)
                  (dag/err :invalid-review-thread-response
                           "GitHub returned incomplete review thread state")
                  (let [total (+ unresolved-count
                                 (count (remove :isResolved (:nodes threads))))]
                    (if (:hasNextPage page-info)
                      (let [next-cursor (:endCursor page-info)]
                        (if-not (valid-next-cursor? next-cursor seen-cursors)
                          (dag/err :invalid-pagination
                                   "GitHub returned an invalid review thread cursor")
                          (recur next-cursor
                                 (conj seen-cursors next-cursor)
                                 total)))
                      (dag/ok {:has-unresolved? (pos? total)
                               :unresolved-count total}))))))))))))

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

  :leave-this-here)
