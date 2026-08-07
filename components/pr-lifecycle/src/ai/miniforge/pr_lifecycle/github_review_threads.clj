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
(ns ai.miniforge.pr-lifecycle.github-review-threads
  "GitHub repository discovery and review-thread provider readback."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.github-review-thread-pages :as pages]
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} repository-coordinates
  [run-command worktree-path]
  (dag/when-let-ok
   [result (run-command
            ["git" "config" "--get" "remote.origin.url"] worktree-path)]
    (let [remote-url (str/trim (get (:data result) :output ""))
          parts (re-find #"github\.com(?::\d+)?[:/]([^/]+)/([^/]+)/?$" remote-url)
          owner (nth parts 1 nil)
          repo (some-> (nth parts 2 nil) (str/replace #"\.git$" ""))]
      (if (and owner (seq repo))
        (dag/ok {:owner owner :repo repo})
        (dag/err :invalid-remote
                 "Could not parse GitHub owner/repository from origin remote")))))

(defn- ^{:stratum 0} review-comment-root-id
  [run-command worktree-path owner repo comment-id]
  (dag/when-let-ok
   [result (run-command
            ["gh" "api" (str "repos/" owner "/" repo
                              "/pulls/comments/" comment-id)]
            worktree-path)]
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
        (dag/err :json-parse-error (.getMessage e))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} get-thread-id
  "Resolve a REST review comment or reply to its GraphQL review thread."
  [run-command graphql-query worktree-path pr-number comment-id]
  (dag/when-let-ok
   [repository-result (repository-coordinates run-command worktree-path)
    root-result (review-comment-root-id
                 run-command
                 worktree-path
                 (:owner (:data repository-result))
                 (:repo (:data repository-result))
                 comment-id)
    thread-result (pages/paginate
                   graphql-query
                   worktree-path
                   (:owner (:data repository-result))
                   (:repo (:data repository-result))
                   pr-number
                   (pages/root-pagination
                    comment-id pr-number (:data root-result)))]
    (let [thread (:data thread-result)]
      (dag/ok {:thread-id (:id thread)
               :is-resolved (:isResolved thread)}))))

(defn ^{:stratum 1} unresolved-review-threads
  "Read all provider pages and summarize unresolved review threads."
  [run-command graphql-query worktree-path pr-number]
  (dag/when-let-ok
   [repository-result (repository-coordinates run-command worktree-path)]
    (pages/paginate
     graphql-query
     worktree-path
     (:owner (:data repository-result))
     (:repo (:data repository-result))
     pr-number
     (pages/unresolved-pagination))))
