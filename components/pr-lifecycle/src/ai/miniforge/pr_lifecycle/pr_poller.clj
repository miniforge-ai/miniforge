(ns ai.miniforge.pr-lifecycle.pr-poller
  "GitHub PR polling adapter with watermark persistence.

   Polls open miniforge-authored PRs for new review comments.
   Watermarks track the last-seen comment timestamp per PR to avoid
   reprocessing. State persisted in ~/.miniforge/state/pr-monitor/.

   Uses the gh CLI for all GitHub API access — no long-lived tokens
   needed beyond the local gh auth configuration."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Watermark persistence

(def ^:private state-dir
  "Base directory for PR monitor state."
  (str (System/getProperty "user.home") "/.miniforge/state/pr-monitor"))

(defn- ensure-state-dir!
  "Ensure the state directory exists."
  []
  (let [dir (io/file state-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn load-watermarks
  "Load watermarks from persistent storage.

   Returns map of PR number → {:last-seen-at string :advanced-at string}.
   Safe to call on restart — returns empty map when no state exists."
  []
  (let [f (io/file state-dir "watermarks.edn")]
    (if (.exists f)
      (try
        (edn/read-string (slurp f))
        (catch Exception _e {}))
      {})))

(defn save-watermarks!
  "Persist watermarks to disk. Idempotent and safe to call every cycle."
  [watermarks]
  (ensure-state-dir!)
  (spit (io/file state-dir "watermarks.edn")
        (pr-str watermarks)))

(defn advance-watermark
  "Advance the watermark for a PR to the given timestamp.

   Returns updated watermarks map (pure — call save-watermarks! to persist)."
  [watermarks pr-number timestamp]
  (assoc watermarks pr-number
         {:last-seen-at timestamp
          :advanced-at (str (java.util.Date.))}))

;------------------------------------------------------------------------------ Layer 0
;; GitHub CLI helpers

(defn- run-gh
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

;------------------------------------------------------------------------------ Layer 1
;; PR listing

(defn poll-open-prs
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
          (dag/ok {:prs (mapv (fn [pr]
                                {:pr/number (:number pr)
                                 :pr/url (:url pr)
                                 :pr/title (:title pr)
                                 :pr/branch (:headRefName pr)
                                 :pr/sha (:headRefOid pr)
                                 :pr/updated-at (:updatedAt pr)})
                              prs)}))
        (catch Exception e
          (dag/err :json-parse-error (.getMessage e))))
      result)))

;------------------------------------------------------------------------------ Layer 1
;; Comment fetching

(defn- parse-gh-comments
  "Parse JSON output from gh api into normalized comment maps."
  [raw comment-type]
  (when-not (str/blank? raw)
    (try
      (let [parsed (json/parse-string raw true)]
        (mapv (fn [c]
                (cond-> {:comment/id (:id c)
                         :comment/body (:body c)
                         :comment/author (get-in c [:user :login])
                         :comment/created-at (:created_at c)
                         :comment/type comment-type}
                  ;; Review comments have file/line context
                  (:path c)
                  (assoc :comment/path (:path c))

                  (:original_line c)
                  (assoc :comment/line (:original_line c))

                  (:line c)
                  (assoc :comment/line (:line c))))
              parsed))
      (catch Exception _e []))))

(defn fetch-pr-comments
  "Fetch all comments for a PR via GitHub REST API.

   Fetches both review comments (inline code comments) and issue comments
   (general PR conversation comments).

   Arguments:
   - worktree-path: Path to git repo
   - pr-number: PR number

   Returns DAG result with :comments vector of normalized comment maps:
   [{:comment/id int :comment/body string :comment/author string
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

(defn comments-since
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
;; Comment posting

(defn post-comment
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
;; Orchestrated polling

(defn poll-pr-for-new-comments
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
  (let [wm (get watermarks pr-number)
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
  (load-watermarks)
  ;; => {}

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
  (let [wm (load-watermarks)]
    (poll-pr-for-new-comments "/path/to/repo" 123 wm nil))

  :leave-this-here)
