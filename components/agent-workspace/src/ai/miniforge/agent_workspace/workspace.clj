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
(ns ai.miniforge.agent-workspace.workspace
  "Clone and deliver one multi-repository workspace. Both refuse before any
   command runs when a repository is outside the PR grant's allowed set or
   the grant does not cover the run."
  (:require
   [ai.miniforge.agent-workspace.admission :as admission]
   [ai.miniforge.agent-workspace.clone :as clone]
   [ai.miniforge.agent-workspace.delivery :as delivery]
   [ai.miniforge.agent-workspace.git :as git]
   [ai.miniforge.agent-workspace.messages :as msg]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} reason-types
  "Anomaly type of each refusal reason."
  {:anomalies.agent-workspace/checkout-mismatch :conflict})

(defn- ^{:stratum 0} checkout-refusal
  "Nil when a checkout names the path and task branch this workspace's root
   and lease give its id; a delivery never pushes a branch it was handed."
  [root lease checkout]
  (when-not (and (= (:repo/path checkout) (git/repo-path root (:repo/id checkout)))
                 (= (:repo/task-branch checkout) (git/task-branch (:lease/task-id lease))))
    :anomalies.agent-workspace/checkout-mismatch))

(defn- ^{:stratum 0} admission-refusal
  [wc acting lease grant repo-ids]
  (let [now ((:workspace/clock wc))]
    (or (admission/tenant-refusal acting lease)
        (admission/repo-refusal grant repo-ids)
        (some #(admission/grant-refusal grant acting lease % now) repo-ids))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} refusal [reason]
  (anomaly/sub-anomaly (get reason-types reason :unauthorized) reason (msg/t reason) {}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} clone!
  [wc acting lease grant request]
  (let [refused (admission-refusal wc acting lease grant (mapv :repo/id (:workspace/repos request)))
        checkouts (when-not refused (clone/clone! wc acting lease request))]
    (cond
      refused (refusal refused)
      (anomaly/anomaly? checkouts) checkouts
      :else {:workspace/lease lease :workspace/root (:workspace/root request) :workspace/checkouts checkouts})))

(defn ^{:stratum 2} deliver!
  [wc acting grant workspace]
  (let [lease (:workspace/lease workspace)
        checkouts (:workspace/checkouts workspace)
        refused (or (admission-refusal wc acting lease grant (mapv :repo/id checkouts))
                    (some #(checkout-refusal (:workspace/root workspace) lease %) checkouts))]
    (if refused
      (refusal refused)
      (mapv #(delivery/deliver-one! wc acting lease grant %) checkouts))))
