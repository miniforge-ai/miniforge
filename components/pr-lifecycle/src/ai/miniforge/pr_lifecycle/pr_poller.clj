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
(ns ai.miniforge.pr-lifecycle.pr-poller
  "GitHub PR polling adapter with watermark persistence.

   Polls open miniforge-authored PRs for new review comments.
   Watermarks track the last-seen comment timestamp per PR to avoid
   reprocessing. They are saved per repository (see monitor-store), and each
   save merges with what other monitors of that repository already saved.

   Uses the gh CLI for all GitHub API access — no long-lived tokens
   needed beyond the local gh auth configuration."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.messages :as msg]
   [ai.miniforge.pr-lifecycle.monitor-store :as store]
   [ai.miniforge.schema.interface :as schema]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Schemas + watermark persistence
(def ^{:stratum 0} WatermarkEntry
  [:map
   [:last-seen-at :string]
   [:advanced-at :string]])

(def ^{:stratum 0} PrInfo
  [:map
   [:pr/number int?]
   [:pr/url :string]
   [:pr/title :string]
   [:pr/branch :string]
   [:pr/sha :string]
   [:pr/updated-at :string]])

(def ^{:stratum 0} PrComment
  [:map
   [:comment/id int?]
   [:comment/body {:optional true} [:maybe :string]]
   [:comment/author {:optional true} [:maybe :string]]
   [:comment/author-association {:optional true} [:maybe :string]]
   [:comment/created-at :string]
   [:comment/type keyword?]
   [:comment/path {:optional true} [:maybe :string]]
   [:comment/line {:optional true} [:maybe int?]]])

(defn- ^{:stratum 0} validate!
  [result-schema value]
  (schema/validate result-schema value))

(def ^{:stratum 0} ^:private watermarks-file-name
  "watermarks.edn")

(defn ^{:stratum 0} empty-watermarks
  "Watermarks of repo `repo-slug` before any comment was seen."
  [repo-slug]
  {:repo-slug repo-slug :prs {}})

(defn- ^{:stratum 0} invalid-watermarks
  [file]
  (anomaly/anomaly :invalid-input
                   (msg/system-t :monitor-state/invalid-watermarks {:path (str file)})
                   {:monitor-state/path (str file)}))

;; GitHub CLI helpers
(defn- ^{:stratum 0} run-gh
  "Run a gh CLI command and return DAG result.

   Arguments:
   - args: Command arguments as vector of strings
   - worktree-path: Directory for command execution (git context)"
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
                  :command (vec args)})))
    (catch Exception e
      (dag/err :gh-exception (.getMessage e)))))

(defn ^{:stratum 0} merge-watermarks
  "Per PR, the entry with the later :last-seen-at from `on-disk` or `mine`
   (a tie keeps `mine`). A monitor saving a stale copy can then neither drop
   PRs another monitor saved nor move a PR's watermark back."
  [on-disk mine]
  (merge-with (fn [disk-entry my-entry]
                (if (pos? (compare (str (:last-seen-at disk-entry))
                                   (str (:last-seen-at my-entry))))
                  disk-entry
                  my-entry))
              on-disk
              mine))

(defn ^{:stratum 0} comments-since
  "Filter comments to only those created after a watermark timestamp.

   Arguments:
   - comments: Vector of comment maps with :comment/created-at
   - since: Timestamp string (ISO 8601) or nil for all comments

   Returns filtered comments sorted by creation time."
  [comments since]
  (if-not since
    (vec (sort-by :comment/created-at comments))
    (->> comments
         (filter #(pos? (compare (str (:comment/created-at %)) (str since))))
         (sort-by :comment/created-at)
         vec)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} PrWatermarks
  "PR number → watermark; also what a repo's watermarks.edn holds."
  [:map-of int? WatermarkEntry])

(def ^{:stratum 1} Watermarks
  "The watermarks of one repository, with the slug they are saved under."
  [:map [:repo-slug [:maybe :string]] [:prs [:map-of int? WatermarkEntry]]])

;; PR listing
(defn- ^{:stratum 1} normalize-pr
  [pr]
  (validate!
   PrInfo
   {:pr/number (:number pr)
    :pr/url (:url pr)
    :pr/title (:title pr)
    :pr/branch (:headRefName pr)
    :pr/sha (:headRefOid pr)
    :pr/updated-at (:updatedAt pr)}))

(defn ^{:stratum 1} current-github-login
  "Resolve the authenticated GitHub login via `gh api user --jq .login`.

   Used as the default `:self-author` for PR monitoring so the monitor loop
   can find PRs this instance authored when no self-author is configured.
   Returns the login string, or nil when gh is unauthenticated/unavailable."
  [worktree-path]
  (let [result (run-gh ["gh" "api" "user" "--jq" ".login"] worktree-path)]
    (when (dag/ok? result)
      (let [login (str/trim (:output (:data result) ""))]
        (when (seq login) login)))))

(defn ^{:stratum 1} resolve-repo-slug
  "owner/name of the repository gh picks for `worktree-path`: the one
   `{owner}/{repo}` expands to in `fetch-pr-comments`. nil when gh cannot
   tell."
  [worktree-path]
  (let [result (run-gh ["gh" "repo" "view" "--json" "nameWithOwner" "--jq" ".nameWithOwner"]
                       worktree-path)]
    (when (dag/ok? result)
      (let [slug (str/trim (:output (:data result) ""))]
        (when (seq slug) slug)))))

;; Comment fetching
(defn- ^{:stratum 1} normalize-comment
  [comment comment-type]
  (validate!
   PrComment
   (cond-> {:comment/id (:id comment)
            :comment/body (:body comment)
            :comment/author (get-in comment [:user :login])
            ;; OWNER / MEMBER / COLLABORATOR / CONTRIBUTOR / NONE ... —
            ;; the classifier's trust gate reads it.
            :comment/author-association (:author_association comment)
            :comment/created-at (:created_at comment)
            :comment/type comment-type}
     (:path comment)
     (assoc :comment/path (:path comment))

     (:original_line comment)
     (assoc :comment/line (:original_line comment))

     (:line comment)
     (assoc :comment/line (:line comment)))))

;; Comment posting
(defn ^{:stratum 1} post-comment
  "Post an issue comment on a PR.

   Arguments:
   - worktree-path: Path to git repo
   - pr-number: PR number
   - body: Comment body text

   Returns DAG result with :comment-posted true on success."
  [worktree-path pr-number body]
  (let [result (run-gh
                ["gh" "pr" "comment" (str pr-number)
                 "--body" body]
                worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:comment-posted true
               :pr-number pr-number})
      result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-watermarks
  "The watermarks saved for repo `repo-slug` (owner/name), as {:repo-slug s
   :prs {pr-number {:last-seen-at string :advanced-at string}}}. An anomaly
   when the slug is not owner/name or the file does not hold a watermark
   map, since then nobody can tell which comments were handled."
  [repo-slug]
  (let [file   (store/repo-file repo-slug watermarks-file-name)
        loaded (if file (store/read-map file) (store/no-repo repo-slug))]
    (cond
      (nil? loaded)                       (empty-watermarks repo-slug)
      (anomaly/anomaly? loaded)           loaded
      (schema/valid? PrWatermarks loaded) (assoc (empty-watermarks repo-slug) :prs loaded)
      :else                               (invalid-watermarks file))))

(defn ^{:stratum 2} save-watermarks!
  "Merge `watermarks` into those saved for their repo (see
   `merge-watermarks`) under the file lock, and write them atomically.
   Returns the merged watermarks, which include PRs other monitors saved, or
   an anomaly with nothing written. Every save carries every PR, so the next
   successful save also writes what a failed one missed."
  [{:keys [repo-slug prs]}]
  (let [file  (store/repo-file repo-slug watermarks-file-name)
        saved (if file
                (store/update-file! file #(if (or (nil? %) (schema/valid? PrWatermarks %))
                                            (merge-watermarks % prs)
                                            %))
                (store/no-repo repo-slug))]
    (cond
      (anomaly/anomaly? saved)           saved
      (schema/valid? PrWatermarks saved) (assoc (empty-watermarks repo-slug) :prs saved)
      :else                              (invalid-watermarks file))))

(defn ^{:stratum 2} advance-watermark
  "Advance the watermark for a PR to the given timestamp.

   Returns updated watermarks map (pure — call save-watermarks! to persist)."
  [watermarks pr-number timestamp]
  (validate!
   Watermarks
   (assoc-in watermarks [:prs pr-number]
             {:last-seen-at timestamp
              :advanced-at (str (java.util.Date.))})))

(defn ^{:stratum 2} poll-open-prs
  "Poll GitHub for open PRs authored by a given login.

   Arguments:
   - worktree-path: Path to git repo (provides gh auth context)
   - author: GitHub login to filter by (e.g. \"miniforge[bot]\")

   Returns DAG result with :prs vector:
   [{:pr/number int :pr/url string :pr/title string
     :pr/branch string :pr/sha string :pr/updated-at string}]"
  [worktree-path author]
  (let [result (run-gh
                ["gh" "pr" "list"
                 "--author" author
                 "--state" "open"
                 "--json" "number,url,title,headRefName,headRefOid,updatedAt"
                 "--limit" "50"]
                worktree-path)]
    (if (dag/ok? result)
      (try
        (let [raw (:output (:data result))
              prs (if (str/blank? raw)
                    []
                    (json/parse-string raw true))]
          (dag/ok {:prs (into [] (map normalize-pr) prs)}))
        (catch Exception e
          (dag/err :json-parse-error (.getMessage e))))
      result)))

(defn- ^{:stratum 2} parse-gh-comments
  "Parse JSON output from gh api into normalized comment maps."
  [raw comment-type]
  (when-not (str/blank? raw)
    (try
      (let [parsed (json/parse-string raw true)]
        (into [] (map #(normalize-comment % comment-type)) parsed))
      (catch Exception _e []))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} fetch-pr-comments
  "Fetch all comments for a PR via GitHub REST API.

   Fetches both review comments (inline code comments) and issue comments
   (general PR conversation comments).

   Arguments:
   - worktree-path: Path to git repo
   - pr-number: PR number

   Returns DAG result with :comments vector of normalized comment maps:
   [{:comment/id int :comment/body string :comment/author string
     :comment/author-association string
     :comment/created-at string :comment/type keyword
     :comment/path string :comment/line int}]"
  [worktree-path pr-number]
  (let [;; Fetch inline review comments
        review-result (run-gh
                       ["gh" "api"
                        (str "repos/{owner}/{repo}/pulls/" pr-number "/comments?per_page=100")]
                       worktree-path)
        ;; Fetch general issue/conversation comments
        issue-result (run-gh
                      ["gh" "api"
                       (str "repos/{owner}/{repo}/issues/" pr-number "/comments?per_page=100")]
                      worktree-path)]

    (cond
      ;; Both failed
      (and (dag/err? review-result) (dag/err? issue-result))
      (dag/err :fetch-comments-failed
               "Failed to fetch both review and issue comments"
               {:review-error (:error review-result)
                :issue-error (:error issue-result)})

      ;; At least one succeeded — merge what we have
      :else
      (let [review-comments (when (dag/ok? review-result)
                              (parse-gh-comments (:output (:data review-result))
                                                 :review-comment))
            issue-comments (when (dag/ok? issue-result)
                             (parse-gh-comments (:output (:data issue-result))
                                                :issue-comment))
            all-comments (into (vec (or review-comments []))
                               (or issue-comments []))]
        (dag/ok {:comments all-comments})))))

;------------------------------------------------------------------------------ Layer 4

;; Orchestrated polling
(defn ^{:stratum 4} poll-pr-for-new-comments
  "Poll a single PR for new comments since its watermark.

   Fetches comments, filters by watermark, advances watermark.
   Does NOT persist watermarks — caller must call save-watermarks!.

   Arguments:
   - worktree-path: Path to git repo
   - pr-number: PR number
   - watermarks: Current watermarks map
   - logger: Optional logger

   Returns DAG result with:
   {:new-comments [...] :watermarks updated-map :pr-number int}"
  [worktree-path pr-number watermarks logger]
  (let [wm (get-in watermarks [:prs pr-number])
        since (:last-seen-at wm)
        result (fetch-pr-comments worktree-path pr-number)]
    (if (dag/ok? result)
      (let [all-comments (:comments (:data result))
            new-comments (comments-since all-comments since)
            latest-ts (when (seq new-comments)
                        (:comment/created-at (last new-comments)))
            updated-wm (if latest-ts
                         (advance-watermark watermarks pr-number latest-ts)
                         watermarks)]
        (when (and logger (seq new-comments))
          (log/info logger :pr-monitor :poller/new-comments
                    {:message (str (count new-comments) " new comment(s) on PR #" pr-number)
                     :data {:pr-number pr-number
                            :count (count new-comments)}}))
        (dag/ok {:new-comments new-comments
                 :watermarks updated-wm
                 :pr-number pr-number}))
      (do
        (when logger
          (log/warn logger :pr-monitor :poller/fetch-failed
                    {:message "Failed to fetch comments"
                     :data {:pr-number pr-number
                            :error (:error result)}}))
        result))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load/save watermarks
  (load-watermarks "miniforge-ai/miniforge")
  ;; => {:repo-slug "miniforge-ai/miniforge" :prs {}}

  ;; Poll open PRs
  (poll-open-prs "/path/to/repo" "miniforge[bot]")
  ;; => {:success true :data {:prs [{:pr/number 123 ...}]}}

  ;; Fetch comments for a PR
  (fetch-pr-comments "/path/to/repo" 123)
  ;; => {:success true :data {:comments [{:comment/id 456 :comment/body "..." ...}]}}

  ;; Filter since timestamp
  (comments-since [{:comment/created-at "2026-03-30T10:00:00Z" :comment/body "old"}
                   {:comment/created-at "2026-03-31T10:00:00Z" :comment/body "new"}]
                  "2026-03-30T12:00:00Z")
  ;; => [{:comment/created-at "2026-03-31T10:00:00Z" :comment/body "new"}]

  ;; Post a comment
  (post-comment "/path/to/repo" 123 "Fixed in commit abc123")

  ;; Orchestrated poll with watermark
  (let [wm (load-watermarks "miniforge-ai/miniforge")]
    (poll-pr-for-new-comments "/path/to/repo" 123 wm nil))

  :leave-this-here)
