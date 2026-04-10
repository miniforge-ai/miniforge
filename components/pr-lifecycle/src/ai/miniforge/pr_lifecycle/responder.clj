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

    (let [comments-result (poller/fetch-pr-comments worktree-path number)]
      (when (dag/err? comments-result)
        (throw (ex-info "Failed to fetch PR comments"
                        {:pr-number number
                         :error (get-in comments-result [:error :message])})))

      (let [all-comments (get-in comments-result [:data :comments])
            actionable (filter-actionable-comments all-comments)
            groups (group-comments-by-file actionable)]

        (if (empty? groups)
          {:pr-number number
           :comments-found 0
           :files-processed 0
           :fixes []
           :pushed? false}

          (let [fixes (mapv
                       (fn [{:keys [path comment-ids] :as group}]
                         (let [spec (build-fix-spec group)
                               result (try
                                        (run-fix-fn spec opts)
                                        (catch Exception e
                                          {:error (.getMessage e)}))
                               succeeded? (and (map? result)
                                               (not (:error result))
                                               (not= :failed (get result :execution/status)))
                               reply-results (when succeeded?
                                               (mapv #(reply-and-resolve!
                                                       worktree-path number %
                                                       "Fixed in latest push. Changes address the review feedback.")
                                                     comment-ids))]
                           {:path path
                            :succeeded? succeeded?
                            :comment-ids comment-ids
                            :replied? (when reply-results (every? :replied? reply-results))
                            :resolved? (when reply-results (every? :resolved? reply-results))
                            :error (when-not succeeded? (get result :error))}))
                       groups)
                any-succeeded? (some :succeeded? fixes)
                pushed? (if any-succeeded? (push-fn) false)]
            {:pr-number number
             :comments-found (count actionable)
             :files-processed (count groups)
             :fixes fixes
             :pushed? pushed?}))))))
