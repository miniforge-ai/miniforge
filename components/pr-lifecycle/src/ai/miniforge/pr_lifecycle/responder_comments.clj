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
(ns ai.miniforge.pr-lifecycle.responder-comments
  "Review comments a PR responder acts on: fetched, filtered to trusted
   human code-review comments, and grouped by file. Split out of
   `ai.miniforge.pr-lifecycle.responder` (rule 210)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.classifier :as classifier]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private bot-authors
  "Authors to exclude from comment processing."
  #{"github-actions" "github-actions[bot]" "miniforge[bot]"})

(defn ^{:stratum 0} group-comments-by-file
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

(defn ^{:stratum 1} filter-actionable-comments
  "Filter review comments to unresolved, non-bot, code-review comments
   from OWNER / MEMBER / COLLABORATOR authors (see
   `classifier/trusted-author?`); anyone else's text would go into a fix
   workflow that pushes. Returns vector of normalized comment maps."
  [comments]
  (->> comments
       (remove #(contains? bot-authors (:comment/author %)))
       (filter #(classifier/trusted-author? % nil))
       (filter #(= :review-comment (:comment/type %)))
       vec))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} fetch-actionable-comments-result
  "Anomaly-returning fetch + filter. Returns
   `{:comments vector :groups vector}` on success, or a `:fault`
   anomaly when the upstream `poller/fetch-pr-comments` errors.

   This is the canonical, anomaly-returning entry point. The
   orchestrator returns the anomaly unchanged so callers can decide
   how to surface it."
  [worktree-path pr-number]
  (let [result (poller/fetch-pr-comments worktree-path pr-number)]
    (if (dag/err? result)
      (anomaly/anomaly :fault
                       "Failed to fetch PR comments"
                       {:pr-number pr-number
                        :error (get-in result [:error :message])})
      (let [all (get-in result [:data :comments])
            actionable (filter-actionable-comments all)]
        {:comments actionable
         :groups (group-comments-by-file actionable)}))))

(comment ::responder-comments)
