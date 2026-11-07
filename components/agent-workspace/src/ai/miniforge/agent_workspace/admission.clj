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
(ns ai.miniforge.agent-workspace.admission
  "Which repositories a task may touch: exactly the set its PR grant names
   in `:workspace/repos`. The grant must be a live root `:effect/pr-create`
   grant issued to the acting principal for this tenant and run."
  (:require
   [ai.miniforge.execution-grant.interface :as grant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pr-effect
  "The grant effect class one repository's pull request spends."
  :effect/pr-create)

(defn ^{:stratum 0} allowed-repos [grant] (get-in grant [:grant/scope :workspace/repos] #{}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pr-scope
  "The effect scope one repository's PR presents: the run, the allowed set
   exactly as granted, and the repository."
  [lease grant repo-id]
  {:workspace/tenant-id (:lease/tenant-id lease)
   :workspace/run-id (:lease/run-id lease)
   :workspace/repos (allowed-repos grant)
   :repo/id repo-id})

(defn ^{:stratum 1} repo-refusal
  "Nil when every repository is in the grant's allowed set."
  [grant repo-ids]
  (when-not (every? (allowed-repos grant) repo-ids)
    :anomalies.agent-workspace/repo-not-allowed))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} grant-refusal
  "Nil when `grant` authorizes a PR for `repo-id` in the leased run at `now`."
  [grant acting lease repo-id now]
  (let [result (grant/authorize grant {:effect/scope (pr-scope lease grant repo-id) :usage/count 1} now)]
    (cond
      (nil? grant) :anomalies.agent-workspace/grant-absent
      (not= pr-effect (:grant/effect-class grant)) :anomalies.agent-workspace/grant-wrong-effect
      (not= (str (:acting/principal-id acting)) (:grant/principal grant)) :anomalies.agent-workspace/grant-wrong-principal
      (not (grant/authorized? result)) :anomalies.agent-workspace/grant-denied)))
