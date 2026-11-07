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
(ns ai.miniforge.agent-workspace.delivery
  "One pull-request effect per repository, through effect-transaction under
   a fixed key for tenant, run, task and repository: a retried delivery
   finds the record an earlier one left and opens no second PR. Pushing the
   task branch and opening the PR happen inside the committed effect, after
   the PR grant is checked again."
  (:require
   [ai.miniforge.agent-workspace.admission :as admission]
   [ai.miniforge.agent-workspace.git :as git]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.anomaly.interface :as anomaly])
  (:import
   [java.nio.charset StandardCharsets]
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} effect-id
  "The fixed key of one repository's PR effect for a task."
  [lease repo-id]
  (UUID/nameUUIDFromBytes
   (.getBytes (pr-str [admission/pr-effect (str (:lease/tenant-id lease)) (str (:lease/run-id lease))
                       (str (:lease/task-id lease)) repo-id])
              StandardCharsets/UTF_8)))

(defn- ^{:stratum 0} proposal
  "What the PR effect is bound to: the grant's scope plus this repository's
   branch, base and head."
  [lease grant checkout head-sha]
  (merge (admission/pr-scope lease grant (:repo/id checkout))
         {:repo/task-branch (:repo/task-branch checkout)
          :repo/base-sha (:repo/base-sha checkout)
          :repo/head-sha head-sha}))

(defn- ^{:stratum 0} delivered
  [checkout record]
  {:repo/id (:repo/id checkout)
   :delivery/state (:effect/state record)
   :delivery/effect-id (:effect/id record)
   :delivery/pull-request (:effect/observed record)})

(defn- ^{:stratum 0} open!
  "The effect itself: push the task branch to the id's URL, then open the
   PR for the record's proposal through the workspace's PR port. Reports
   what the port answered."
  [wc acting lease checkout record]
  (let [url (git/repo-url wc (:repo/id checkout))
        pushed (git/git! wc acting lease (git/push-argv (:repo/path checkout) url (:repo/task-branch checkout)))
        opened (when-not (anomaly/anomaly? pushed) ((:workspace/open-pr! wc) (:effect/proposal record)))]
    (cond
      (anomaly/anomaly? pushed) {:effect/outcome :failed :effect/failure (:anomaly/message pushed)}
      (anomaly/anomaly? opened) {:effect/outcome :unknown-outcome :effect/observed {:anomaly (:anomaly/message opened)}}
      :else {:effect/outcome :succeeded :effect/observed opened})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} recorded-or-proposed
  [wc lease grant checkout head-sha]
  (let [id (effect-id lease (:repo/id checkout))
        found (fx/read-record (:workspace/store-dir wc) id)]
    (if (some? found)
      found
      (fx/propose! (:workspace/store-dir wc) {:effect-id id
                                              :effect-class admission/pr-effect
                                              :grant-id (:grant/id grant)
                                              :proposal (proposal lease grant checkout head-sha)}
                   ((:workspace/clock wc))))))

(defn- ^{:stratum 1} committed
  "Commit a proposed PR effect: the grant is checked again, then `open!` runs."
  [wc acting lease grant checkout record]
  (let [settled (fx/commit! (:workspace/store-dir wc) record grant {} ((:workspace/clock wc))
                            #(open! wc acting lease checkout record))]
    (if (anomaly/anomaly? settled) settled (delivered checkout settled))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} deliver-one!
  "Deliver one checkout: commit its PR effect once, or report the record a
   previous delivery left (succeeded, failed, or awaiting reconciliation)."
  [wc acting lease grant checkout]
  (let [head (git/git! wc acting lease (git/head-argv (:repo/path checkout)))
        record (if (anomaly/anomaly? head) head (recorded-or-proposed wc lease grant checkout (git/sha-of head)))]
    (cond
      (anomaly/anomaly? record) record
      (= :proposed (:effect/state record)) (committed wc acting lease grant checkout record)
      :else (delivered checkout record))))
