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

   `responder-comments` fetches and filters the comments, `responder-fix`
   runs one file's fix and replies; this namespace parses the PR URL and
   orchestrates (respond-to-comments!)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.pr-lifecycle.responder-comments :as review-comments]
   [ai.miniforge.pr-lifecycle.responder-fix :as comment-fix]))

;------------------------------------------------------------------------------ Layer 0

;; PR URL parsing and comment filtering
(defn ^{:stratum 0} parse-pr-url
  "Extract owner, repo, and PR number from a GitHub PR URL.
   Returns {:owner :repo :number} or nil."
  [url]
  (when-let [match (re-find #"github\.com/([^/]+)/([^/]+)/pull/(\d+)" (str url))]
    {:owner (nth match 1)
     :repo (nth match 2)
     :number (parse-long (nth match 3))}))

(defn- ^{:stratum 0} respond-result
  "Build the response summary map."
  [pr-number comments-found files-processed fixes pushed?]
  {:pr-number pr-number
   :comments-found comments-found
   :files-processed files-processed
   :fixes fixes
   :pushed? pushed?})

(def ^{:stratum 0} group-comments-by-file
  "Group comments by file path; see `responder-comments`."
  review-comments/group-comments-by-file)

(def ^{:stratum 0} filter-actionable-comments
  "Keep trusted human code-review comments; see `responder-comments`."
  review-comments/filter-actionable-comments)

(def ^{:stratum 0} fetch-actionable-comments-result
  "Fetch, filter and group a PR's comments; see `responder-comments`."
  review-comments/fetch-actionable-comments-result)

(def ^{:stratum 0} build-fix-spec
  "The fix workflow spec for one comment group; see `responder-fix`."
  comment-fix/build-fix-spec)

(def ^{:stratum 0} reply-and-resolve!
  "Reply to a review comment and resolve its thread; see `responder-fix`."
  comment-fix/reply-and-resolve!)

(def ^{:stratum 0} gather-context
  "Gather the PR diff and file contents; see `responder-fix`."
  comment-fix/gather-context)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} parse-pr-url-result
  "Anomaly-returning version of `parse-pr-url`. Returns the parsed map
   when `url` is a recognizable GitHub PR URL, or an `:invalid-input`
   anomaly carrying `:url` in `:anomaly/data`.

   This is the canonical, anomaly-returning entry point. The
   orchestrator returns the anomaly unchanged so callers can decide
   how to surface it."
  [url]
  (or (parse-pr-url url)
      (anomaly/anomaly :invalid-input
                       "Could not parse PR number from URL"
                       {:url url})))

;------------------------------------------------------------------------------ Layer 2

;; Main entry point
(defn ^{:stratum 2} respond-to-comments!
  "Fetch review comments, generate fixes in parallel, push, reply, and resolve.

   Comment groups (one per file) are processed in parallel via futures.
   PR diff and file contents are gathered once upfront and shared across
   all parallel fix workflows.

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
    :pushed? bool}

   Returns an anomaly map unchanged when the PR URL is invalid or PR
   comment fetching fails."
  [pr-url worktree-path run-fix-fn push-fn opts]
  (let [parsed (parse-pr-url-result pr-url)]
    (if (anomaly/anomaly? parsed)
      parsed
      (let [{:keys [number]} parsed
            fetch-result (review-comments/fetch-actionable-comments-result worktree-path number)]
        (if (anomaly/anomaly? fetch-result)
          fetch-result
          (let [{:keys [comments groups]} fetch-result]
            (if (empty? groups)
              (respond-result number (count comments) 0 [] false)
              (let [context (comment-fix/gather-context worktree-path number groups)
                    ;; Process comment groups in parallel
                    fix-futures (mapv #(future (comment-fix/process-comment-group
                                               worktree-path number run-fix-fn context opts %))
                                      groups)
                    fixes (mapv deref fix-futures)
                    pushed? (if (some :succeeded? fixes) (push-fn) false)]
                (respond-result number (count comments) (count groups) fixes pushed?)))))))))
