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
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant]
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
  [operations worktree-path pr-number policy head-sha]
  (let [result ((:merge-pr operations)
                worktree-path pr-number :policy policy :head-sha head-sha)]
    {:ok? (dag/ok? result) :error (:error result)}))

(defn- ^{:stratum 0} revoke!
  "Revoke the registered grant of an attempt that ended without a commit,
   so it cannot authorize a later one."
  [context prepared now]
  (when-let [id (get-in prepared [:authority/grant :grant/id])]
    (grant/revoke-stored! (authority/breach-dir context) id
                          :revocation/superseded now)))

(defn- ^{:stratum 0} refused
  "A dag error for `code`, carrying the grant revocation when it failed."
  [code message-key data revoked]
  (dag/err code (messages/t message-key)
           (cond-> data
             (anomaly/anomaly? revoked) (assoc :grant/revocation-failure revoked))))

(defn- ^{:stratum 0} attempt-id
  "This attempt's effect id: the next free attempt at this run and exact
   target. Ids repeat across processes, so two racing for one attempt
   collide on its record. A context with no run gets a fresh id;
   authority then refuses it."
  [context target]
  (if (uuid? (:run-id context))
    (fx/free-attempt-id (transaction/store-dir context)
                        [:effect/merge (:run-id context) (:pr/repo target)
                         (:pr/number target) (:pr/head-sha target)])
    (random-uuid)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} commit-authority!
  [context proposed prepared pr-number now enable! run-gh]
  (let [committed (transaction/commit!
                   context proposed (authority/breach-dir context)
                   (get-in prepared [:authority/grant :grant/id])
                   pr-number enable! run-gh)]
    (cond
      (anomaly/anomaly? committed)
      (refused :merge-commit-refused :merge/commit-refused
               {:commit/result committed} (revoke! context prepared now))

      ;; Refused at the commit-time recheck: no gh command ran.
      (and (= :failed (:effect/state committed))
           (not= :granted (:effect/authority committed)))
      (refused :merge-commit-refused :merge/commit-refused
               {:commit/result committed} (revoke! context prepared now))

      (= :failed (:effect/state committed))
      (let [revoked (revoke! context prepared now)]
        (dag/ok (cond-> committed
                  (anomaly/anomaly? revoked)
                  (assoc :grant/revocation-failure revoked))))

      :else (dag/ok committed))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} transact!
  "Prepare and register authority, then durably propose and commit an
   allowed merge of `target` (`:pr/repo`, `:pr/number`, `:pr/head-sha`).
   The effect id is the next free attempt at this run and target. Every
   attempt that ends before a commit revokes its grant."
  [operations worktree-path target readiness policy context ^Instant now]
  (let [pr-number (:pr/number target)
        run-gh (partial provider-command operations worktree-path)
        enable! (partial enable-merge operations worktree-path pr-number policy)
        effect-id (attempt-id context target)
        prepared (when-not (anomaly/anomaly? effect-id)
                   (authority/prepare context effect-id target readiness
                                      (:method policy) now))]
    (cond
      (anomaly/anomaly? effect-id)
      (dag/err :merge-proposal-failed (messages/t :merge/proposal-failed)
               {:proposal/result effect-id})

      (anomaly/anomaly? prepared)
      (dag/err :merge-authority-unavailable
               (messages/t :merge/authority-unavailable)
               {:authority/result prepared})

      (not (authority/permitted? prepared))
      (refused :merge-authority-denied :merge/authority-denied
               {:effect/id (:effect/id prepared)
                :envelope/decision (get-in prepared
                                           [:authority/envelope
                                            :envelope/decision])}
               (revoke! context prepared now))

      :else
      (let [proposed (transaction/propose! context prepared now)]
        (if (anomaly/anomaly? proposed)
          (refused :merge-proposal-failed :merge/proposal-failed
                   {:proposal/result proposed} (revoke! context prepared now))
          (commit-authority! context proposed prepared pr-number now
                             enable! run-gh))))))
