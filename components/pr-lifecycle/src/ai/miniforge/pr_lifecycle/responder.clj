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

(ns ai.miniforge.pr-lifecycle.responder
  "Automated PR comment response — fetch review comments, generate fixes,
   push, reply, and resolve conversation threads.

   Layer 0: PR URL parsing and comment filtering
   Layer 1: Fix spec generation
   Layer 2: Orchestration (respond-to-comments!)"
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.github :as github]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; PR URL parsing and comment filtering

(defn parse-pr-url
  "Extract owner, repo, and PR number from a GitHub PR URL.
   Returns {:owner :repo :number} or nil."
  [url]
  (when-let [match (re-find #"github\.com/([^/]+)/([^/]+)/pull/(\d+)" (str url))]
    {:owner (nth match 1)
     :repo (nth match 2)
     :number (parse-long (nth match 3))}))

(def ^:private bot-authors
  "Authors to exclude from comment processing."
  #{"github-actions" "github-actions[bot]" "miniforge[bot]"})

(defn filter-actionable-comments
  "Filter review comments to unresolved, non-bot, code-review comments.
   Returns vector of normalized comment maps."
  [comments]
  (->> comments
       (remove #(contains? bot-authors (:comment/author %)))
       (filter #(= :review-comment (:comment/type %)))
       vec))

(defn group-comments-by-file
  "Group comments by their file path for batch processing.
   Returns [{:path :comments :description :comment-ids}]."
  [comments]
  (->> comments
       (filter :comment/path)
       (group-by :comment/path)
       (mapv (fn [[path cs]]
               {:path path
                :comments cs
                :description (str/join "\n\n" (map :comment/body cs))
                :comment-ids (mapv :comment/id cs)}))))

;------------------------------------------------------------------------------ Layer 1
;; Fix spec generation

(defn build-fix-spec
  "Build a workflow spec from a group of review comments on a file.
   The spec drives the canonical-sdlc workflow to implement the fix."
  [{:keys [path description]}]
  {:spec/title (str "Fix review comments on " path)
   :spec/description (str "Review comments to address on " path ":\n\n" description)
   :spec/intent {:type :fix :scope [path]}
   :spec/constraints ["Only modify the specific code referenced by the review comments"
                      "Do not change unrelated code"
                      "Maintain existing tests"]
   :workflow/type :canonical-sdlc})

;------------------------------------------------------------------------------ Layer 2
;; Reply and resolution

(defn reply-and-resolve!
  "Reply to a review comment and resolve its conversation thread.
   Returns {:replied? bool :resolved? bool :error string-or-nil}."
  [worktree-path pr-number comment-id message]
  (let [reply-result (github/reply-to-comment worktree-path pr-number comment-id message)
        replied? (dag/ok? reply-result)
        ;; Resolve the thread if reply succeeded
        resolve-result (when replied?
                         (let [thread-result (github/get-thread-id worktree-path pr-number comment-id)]
                           (when (dag/ok? thread-result)
                             (github/resolve-conversation
                              worktree-path
                              (get-in thread-result [:data :thread-id])))))
        resolved? (and resolve-result (dag/ok? resolve-result))]
    {:replied? replied?
     :resolved? resolved?
     :error (when-not replied?
              (get-in reply-result [:error :message] "Reply failed"))}))

;------------------------------------------------------------------------------ Layer 3
;; Orchestration

(defn- fix-succeeded?
  "True when a workflow result indicates success."
  [result]
  (and (map? result)
       (not (:error result))
       (not= :failed (get result :execution/status))))

(defn- run-fix-for-group
  "Run a fix workflow for a comment group. Returns result map or {:error msg}."
  [run-fix-fn group opts]
  (let [spec (build-fix-spec group)]
    (try
      (run-fix-fn spec opts)
      (catch Exception e
        {:error (.getMessage e)}))))

(defn- reply-to-fixed-comments
  "Reply and resolve all comments in a group after a successful fix."
  [worktree-path pr-number comment-ids]
  (mapv #(reply-and-resolve! worktree-path pr-number %
                             "Fixed in latest push. Changes address the review feedback.")
        comment-ids))

(defn- fix-result
  "Build a normalized fix result map."
  [path comment-ids succeeded? reply-results error]
  {:path path
   :succeeded? succeeded?
   :comment-ids comment-ids
   :replied? (when reply-results (every? :replied? reply-results))
   :resolved? (when reply-results (every? :resolved? reply-results))
   :error error})

(defn- process-comment-group
  "Fix one file's comments, reply on success. Returns fix result map."
  [worktree-path pr-number run-fix-fn opts {:keys [path comment-ids] :as group}]
  (let [result (run-fix-for-group run-fix-fn group opts)
        succeeded? (fix-succeeded? result)]
    (if succeeded?
      (fix-result path comment-ids true
                  (reply-to-fixed-comments worktree-path pr-number comment-ids) nil)
      (fix-result path comment-ids false nil (get result :error)))))

(defn- fetch-actionable-comments
  "Fetch and filter PR comments to actionable review comments.
   Returns {:comments vector :groups vector} or throws on fetch failure."
  [worktree-path pr-number]
  (let [result (poller/fetch-pr-comments worktree-path pr-number)]
    (when (dag/err? result)
      (throw (ex-info "Failed to fetch PR comments"
                      {:pr-number pr-number
                       :error (get-in result [:error :message])})))
    (let [all (get-in result [:data :comments])
          actionable (filter-actionable-comments all)]
      {:comments actionable
       :groups (group-comments-by-file actionable)})))

(defn- respond-result
  "Build the response summary map."
  [pr-number comments-found files-processed fixes pushed?]
  {:pr-number pr-number
   :comments-found comments-found
   :files-processed files-processed
   :fixes fixes
   :pushed? pushed?})

(defn respond-to-comments!
  "Fetch review comments, generate fixes, push, reply, and resolve.

   Arguments:
   - pr-url: GitHub PR URL
   - worktree-path: Path to local git checkout on the PR branch
   - run-fix-fn: (fn [spec opts] result) — runs a fix workflow for a comment group
   - push-fn: (fn [] bool) — pushes changes to remote
   - opts: Additional options passed to run-fix-fn

   Returns:
   {:pr-number int
    :comments-found int
    :files-processed int
    :fixes [{:path :succeeded? :comment-ids :replied? :resolved?}]
    :pushed? bool}"
  [pr-url worktree-path run-fix-fn push-fn opts]
  (let [{:keys [number]} (parse-pr-url pr-url)]
    (when-not number
      (throw (ex-info "Could not parse PR number from URL" {:url pr-url})))
    (let [{:keys [comments groups]} (fetch-actionable-comments worktree-path number)]
      (if (empty? groups)
        (respond-result number 0 0 [] false)
        (let [fixes (mapv #(process-comment-group worktree-path number run-fix-fn opts %) groups)
              pushed? (if (some :succeeded? fixes) (push-fn) false)]
          (respond-result number (count comments) (count groups) fixes pushed?))))))
