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
(ns ai.miniforge.pr-lifecycle.github-review-thread-pages
  "Validated, cycle-safe pagination for GitHub review-thread readback."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private review-threads-query
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
  [cursor seen-cursors]
  (and (string? cursor)
       (not (str/blank? cursor))
       (not (contains? seen-cursors cursor))))

(defn- ^{:stratum 0} page-variables
  [owner repo pr-number cursor]
  (cond-> {:owner owner :repo repo :pr pr-number}
    cursor (assoc :cursor cursor)))

(defn- ^{:stratum 0} select-unresolved
  [unresolved-count nodes]
  (let [page-count (count (remove :isResolved nodes))
        total (+ unresolved-count page-count)]
    #:page{:done? false :state total}))

(defn- ^{:stratum 0} finish-unresolved
  [unresolved-count]
  (let [has-unresolved? (pos? unresolved-count)]
    (dag/ok {:has-unresolved? has-unresolved?
             :unresolved-count unresolved-count})))

(defn- ^{:stratum 0} select-root
  [root-id state nodes]
  (let [matching-thread (some #(when (= root-id
                                       (get-in % [:comments :nodes 0 :databaseId]))
                                 %)
                              nodes)
        found? (some? matching-thread)]
    #:page{:done? found?
           :state state
           :value matching-thread}))

(defn- ^{:stratum 0} finish-missing-root
  [comment-id pr-number _]
  (dag/err :thread-not-found
           "Could not find thread containing comment ID"
           {:comment-id comment-id :pr-number pr-number}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unresolved-pagination
  "Describe full review-thread pagination without embedding behavior anonymously."
  []
  #:pagination{:query review-threads-query
               :valid-page? valid-review-thread-page?
               :select-page select-unresolved
               :initial-state 0
               :finish finish-unresolved})

(defn- ^{:stratum 1} valid-review-thread-root-page?
  [threads]
  (and (valid-review-thread-page? threads)
       (every? #(integer? (get-in % [:comments :nodes 0 :databaseId]))
               (:nodes threads))))

(defn- ^{:stratum 1} read-page
  [graphql-query worktree-path owner repo pr-number cursor query valid-page?]
  (let [variables (page-variables owner repo pr-number cursor)]
    (dag/when-let-ok
     [result (graphql-query query worktree-path :variables variables)]
      (let [threads (get-in (:data result)
                            [:data :repository :pullRequest :reviewThreads])]
        (if (valid-page? threads)
          (dag/ok threads)
          (dag/err :invalid-review-thread-response
                   "GitHub returned incomplete review thread state"))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} root-pagination
  "Describe early-exit pagination for one review-comment root."
  [comment-id pr-number root-id]
  (let [select-page (partial select-root root-id)
        finish (partial finish-missing-root comment-id pr-number)]
    #:pagination{:query review-thread-roots-query
                 :valid-page? valid-review-thread-root-page?
                 :select-page select-page
                 :initial-state nil
                 :finish finish}))

(defn ^{:stratum 2} paginate
  "Apply a named pagination description with validation and cycle rejection."
  [graphql-query worktree-path owner repo pr-number pagination]
  (let [query (:pagination/query pagination)
        valid-page? (:pagination/valid-page? pagination)
        select-page (:pagination/select-page pagination)
        initial-state (:pagination/initial-state pagination)
        finish (:pagination/finish pagination)]
    (loop [cursor nil
           seen-cursors #{}
           state initial-state]
      (dag/when-let-ok
       [page-result (read-page graphql-query worktree-path owner repo pr-number
                               cursor query valid-page?)]
        (let [threads (:data page-result)
              selection (select-page state (:nodes threads))
              page-info (:pageInfo threads)
              next-cursor (:endCursor page-info)
              next-state (:page/state selection)]
          (cond
            (:page/done? selection) (dag/ok (:page/value selection))
            (not (:hasNextPage page-info)) (finish next-state)
            (not (valid-next-cursor? next-cursor seen-cursors))
            (dag/err :invalid-pagination
                     "GitHub returned an invalid review thread cursor")
            :else (recur next-cursor
                         (conj seen-cursors next-cursor)
                         next-state)))))))
