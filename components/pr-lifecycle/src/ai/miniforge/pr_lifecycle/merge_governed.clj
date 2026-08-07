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
(ns ai.miniforge.pr-lifecycle.merge-governed
  "Granted, gated, and durably transacted GitHub merge execution."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.merge-authority :as authority]
   [ai.miniforge.pr-lifecycle.merge-transaction :as transaction]
   [ai.miniforge.pr-lifecycle.messages :as messages])
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

(defn- ^{:stratum 0} propose-authority!
  [context prepared now]
  (if (anomaly/anomaly? prepared)
    prepared
    (transaction/propose! context prepared now)))

(defn- ^{:stratum 0} commit-authority!
  [context proposed prepared pr-number now enable! run-gh]
  (let [committed (transaction/commit!
                   context proposed (:authority/grant prepared)
                   pr-number now enable! run-gh)]
    (if (anomaly/anomaly? committed)
      (dag/err :merge-commit-refused
               (messages/t :merge/commit-refused)
               {:commit/result committed})
      (dag/ok committed))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} transact!
  "Prepare authority, durably propose, then commit an allowed merge."
  [operations worktree-path pr-number repo policy context ^Instant now]
  (let [run-gh (partial provider-command operations worktree-path)
        enable! (partial enable-merge operations worktree-path pr-number policy)
        prepared (authority/prepare context (random-uuid) repo pr-number
                                    (:method policy) now)
        proposed (propose-authority! context prepared now)]
    (cond
      (anomaly/anomaly? prepared)
      (dag/err :merge-authority-unavailable
               (messages/t :merge/authority-unavailable)
               {:authority/result prepared})

      (anomaly/anomaly? proposed)
      (dag/err :merge-proposal-failed
               (messages/t :merge/proposal-failed)
               {:proposal/result proposed})

      (not (authority/permitted? prepared))
      (dag/err :merge-authority-denied
               (messages/t :merge/authority-denied)
               {:effect/id (:effect/id proposed)
                :envelope/decision (get-in prepared
                                           [:authority/envelope
                                            :envelope/decision])})

      :else
      (commit-authority! context proposed prepared pr-number now
                         enable! run-gh))))
