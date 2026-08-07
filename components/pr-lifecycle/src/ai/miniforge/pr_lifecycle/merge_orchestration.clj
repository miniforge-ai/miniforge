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
(ns ai.miniforge.pr-lifecycle.merge-orchestration
  "Application flow for readiness, conflict handling, and transacted merge."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.conflict-resolution :as conflict-resolution]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.merge-transaction :as transaction])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} provider-command
  [operations worktree-path args]
  (let [result ((:run-gh operations) args worktree-path)]
    {:ok? (dag/ok? result)
     :output (:output (:data result))}))

(defn- ^{:stratum 0} enable-merge
  [operations worktree-path pr-number policy]
  (let [result ((:merge-pr operations)
                worktree-path pr-number :policy policy)]
    {:ok? (dag/ok? result) :error (:error result)}))

(defn- ^{:stratum 0} merged-result
  [operations worktree-path pr-number policy context settled merge-sha]
  (let [logger (:logger context)
        {:keys [dag-id run-id task-id pr-id event-bus]} context]
    (when event-bus
      (events/publish! event-bus
                       (events/merged
                        dag-id run-id task-id pr-id merge-sha
                        ((:fetch-labels operations) worktree-path pr-number))
                       logger))
    (when logger
      (log/info logger :pr-lifecycle :merge/success
                {:message "PR merged successfully"
                 :data {:pr-number pr-number :merge/sha merge-sha}}))
    (dag/ok {:merged? true
             :method (:method policy)
             :merge/sha merge-sha
             :effect/id (:effect/id settled)})))

(defn- ^{:stratum 0} pending-result
  [pr-number policy context settled]
  (when-let [logger (:logger context)]
    (log/info logger :pr-lifecycle :merge/auto-merge-pending
              {:message "Auto-merge enabled; merge not yet observed"
               :data {:pr-number pr-number
                      :effect/state (:effect/state settled)}}))
  (dag/ok {:merged? false
           :auto-merge/enabled? true
           :method (:method policy)
           :effect/id (:effect/id settled)}))

(defn- ^{:stratum 0} completed-rebase
  [context new-sha]
  (let [logger (:logger context)
        {:keys [dag-id run-id task-id pr-id event-bus]} context]
    (when event-bus
      (events/publish! event-bus
                       (events/rebase-needed
                        dag-id run-id task-id pr-id new-sha)
                       logger))
    (dag/ok {:merged? false :rebased? true :new-sha new-sha})))

(defn- ^{:stratum 0} attempt-conflict-resolution!
  "Run the configured conflict-resolution sub-workflow when applicable."
  [operations worktree-path pr-number policy context branch-raw]
  (let [resolve-fn (:resolve-fn context)
        state (conflict-resolution/classify-merge-state branch-raw)]
    (when (and (= :conflicting state)
               (:auto-resolve-conflicts? policy)
               resolve-fn)
      (dag/when-let-ok
       [pr-result ((:pr-info operations) worktree-path pr-number)]
        ((:normalize-resolution operations)
         (conflict-resolution/resolve-pr-conflicts!
          {:worktree-path worktree-path
           :pr (:data pr-result)
           :resolve-fn resolve-fn
           :context context}))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} transact!
  "Record merge intent, enable auto-merge, and reconcile the immediate state."
  [operations worktree-path pr-number policy context ^Instant now]
  (let [run-gh (partial provider-command operations worktree-path)
        enable! (partial enable-merge operations worktree-path pr-number policy)
        proposed (transaction/propose!
                  (assoc context :merge/method (:method policy))
                  pr-number (:pr/repo context) now)]
    (transaction/commit! context proposed pr-number now enable! run-gh)))

(defn- ^{:stratum 1} settlement-result
  "Translate one transaction state without overstating a pending merge."
  [operations worktree-path pr-number policy context settled]
  (let [merge-sha (transaction/substantiated-sha settled)]
    (cond
      (= :failed (:effect/state settled))
      (dag/err :merge-failed
               "Merge command failed"
               {:gh-error (:effect/failure settled)})

      merge-sha
      (merged-result operations worktree-path pr-number
                     policy context settled merge-sha)

      :else
      (pending-result pr-number policy context settled))))

(defn- ^{:stratum 1} rebase-stale!
  "Rebase one stale PR and publish the new head when successful."
  [operations worktree-path pr-number context]
  (when-let [logger (:logger context)]
    (log/info logger :pr-lifecycle :merge/rebasing
              {:message "PR is stale, attempting rebase"}))
  (dag/when-let-ok
   [branch-result ((:fetch-branch operations) worktree-path pr-number)
    result ((:rebase-pr operations)
            worktree-path (:branch (:data branch-result)))]
    (completed-rebase context (:new-sha (:data result)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} attempt-merge
  "Attempt a governed merge or the configured branch repair."
  [operations worktree-path pr-number policy context]
  (when-let [logger (:logger context)]
    (log/info logger :pr-lifecycle :merge/attempting
              {:message "Attempting PR merge"
               :data {:pr-number pr-number}}))
  (let [readiness-result ((:evaluate-readiness operations)
                          worktree-path pr-number policy)
        stale? (contains? (set (:blocking readiness-result))
                          :branch-not-up-to-date)]
    (if (:ready? readiness-result)
      (let [now (Instant/now)
            settled (transact! operations worktree-path pr-number
                                 policy context now)]
        (settlement-result operations worktree-path pr-number
                           policy context settled))
      (let [branch-raw (get-in (:checks readiness-result) [:branch :data :raw])
            conflict-result (when stale?
                              (attempt-conflict-resolution!
                               operations worktree-path pr-number
                               policy context branch-raw))]
        (cond
          (some? conflict-result)
          conflict-result

          (and stale? (:auto-rebase-on-stale? policy))
          (rebase-stale! operations worktree-path pr-number context)

          :else
          (dag/err :not-ready
                   "PR is not ready to merge"
                   {:blocking (:blocking readiness-result)}))))))
