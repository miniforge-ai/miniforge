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
(ns ai.miniforge.pr-lifecycle.merge-outcome
  "Honest result and event translation for governed merge transactions."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.merge-transaction :as transaction]
   [ai.miniforge.pr-lifecycle.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

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
                {:message (messages/system-t :merge/success)
                 :data {:pr-number pr-number :merge/sha merge-sha}}))
    (dag/ok {:merged? true
             :method (:method policy)
             :merge/sha merge-sha
             :effect/id (:effect/id settled)})))

(defn- ^{:stratum 0} pending-result
  [pr-number policy context settled]
  (when-let [logger (:logger context)]
    (log/info logger :pr-lifecycle :merge/auto-merge-pending
              {:message (messages/system-t :merge/auto-merge-pending)
               :data {:pr-number pr-number
                      :effect/state (:effect/state settled)}}))
  (dag/ok {:merged? false
           :auto-merge/enabled? true
           :method (:method policy)
           :effect/id (:effect/id settled)}))

(defn- ^{:stratum 0} uncertain-result
  [settled]
  (dag/err :merge-outcome-unknown
           (messages/t :merge/outcome-unknown)
           {:effect/id (:effect/id settled)
            :effect/state (:effect/state settled)
            :effect/failure (:effect/failure settled)}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} settlement-result
  "Translate one transaction state without overstating a pending merge."
  [operations worktree-path pr-number policy context settled]
  (let [merge-sha (transaction/substantiated-sha settled)]
    (cond
      (= :failed (:effect/state settled))
      (dag/err :merge-failed
               (messages/t :merge/command-failed)
               {:gh-error (:effect/failure settled)})

      merge-sha
      (merged-result operations worktree-path pr-number
                     policy context settled merge-sha)

      (:effect/failure settled)
      (uncertain-result settled)

      :else
      (pending-result pr-number policy context settled))))
