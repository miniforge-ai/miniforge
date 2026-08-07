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
   [ai.miniforge.pr-lifecycle.merge-governed :as governed]
   [ai.miniforge.pr-lifecycle.merge-outcome :as outcome]
   [ai.miniforge.pr-lifecycle.messages :as messages])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

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

(defn- ^{:stratum 0} merge-ready!
  [operations worktree-path pr-number policy context]
  (dag/when-let-ok
   [repository-result ((:read-repository operations) worktree-path)
    transaction-result (governed/transact!
                        operations worktree-path pr-number
                        (:pr/repo (:data repository-result))
                        policy context (Instant/now))]
    (outcome/settlement-result operations worktree-path pr-number policy context
                               (:data transaction-result))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} rebase-stale!
  "Rebase one stale PR and publish the new head when successful."
  [operations worktree-path pr-number context]
  (when-let [logger (:logger context)]
    (log/info logger :pr-lifecycle :merge/rebasing
              {:message (messages/system-t :merge/rebasing)}))
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
              {:message (messages/system-t :merge/attempting)
               :data {:pr-number pr-number}}))
  (let [readiness-result ((:evaluate-readiness operations)
                          worktree-path pr-number policy)
        stale? (some #{:branch-not-up-to-date}
                     (:blocking readiness-result))]
    (if (:ready? readiness-result)
      (merge-ready! operations worktree-path pr-number policy context)
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
                   (messages/t :merge/not-ready)
                   {:blocking (:blocking readiness-result)}))))))
