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
(ns ai.miniforge.agent-worker.admission
  "Whether a worker call may run: the acting tenant owns the binding, the
   binding names the environment's current lease, and a live spend grant
   issued to the acting principal covers the run and bounds the budget.
   Each check returns nil or the refusal reason."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.execution-grant.interface :as grant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} spend-effect
  "The grant effect class a worker attempt spends against."
  :effect/spend)

(def ^{:stratum 0} grant-outcome->reason
  {:absent :anomalies.agent-worker/grant-absent
   :inactive :anomalies.agent-worker/grant-inactive
   :scope-mismatch :anomalies.agent-worker/grant-scope-mismatch
   :exceeded :anomalies.agent-worker/grant-exceeded})

(defn ^{:stratum 0} binding-refusal
  "Nil when `binding` belongs to the acting tenant and names `lease` exactly."
  [acting lease binding]
  (cond
    (not= (:acting/tenant-id acting) (:worker/tenant-id binding))
    :anomalies.agent-worker/forged-tenant

    (not= (:acting/principal-id acting) (:worker/principal-id binding))
    :anomalies.agent-worker/forged-principal

    (not= [(:lease/tenant-id lease) (:lease/run-id lease) (:lease/task-id lease)]
          [(:worker/tenant-id binding) (:worker/run-id binding) (:worker/task-id binding)])
    :anomalies.agent-worker/binding-mismatch

    (not= (:lease/generation lease) (:worker/lease-generation binding))
    :anomalies.agent-worker/stale-lease

    (not= (:lease/environment-uid lease) (:worker/environment-uid binding))
    :anomalies.agent-worker/binding-mismatch))

(defn ^{:stratum 0} config-refusal
  "Nil when `config` is the configuration the binding's digest names."
  [binding config]
  (when (not= (content-hash/content-hash config) (:worker/config-digest binding))
    :anomalies.agent-worker/config-digest-mismatch))

(def ^{:stratum 0} attempt-count
  "Effects one worker attempt presents against a grant's count ceiling."
  1)

(defn ^{:stratum 0} budget-refusal
  "A cost ceiling on the grant needs a configured budget, and any configured
   budget needs a worker that can enforce or at least observe it. A token
   ceiling is refused as unenforceable: an attempt presents no token
   reading, so authorization could not hold it to one."
  [grant config capabilities]
  (let [constraints (:grant/constraints grant)
        budget (:config/max-budget-usd config)]
    (cond
      (some? (:constraint/max-tokens constraints)) :anomalies.agent-worker/budget-unenforceable
      (and (some? (:constraint/max-cost-usd constraints)) (nil? budget)) :anomalies.agent-worker/budget-unbounded
      (and (some? budget) (= :budget/none (:capabilities/budget capabilities))) :anomalies.agent-worker/budget-unenforceable)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} spend-usage
  "The usage an attempt presents: its run and task, one attempt, and the
   configured budget when there is one."
  [binding config]
  (cond-> {:effect/scope {:worker/tenant-id (:worker/tenant-id binding)
                          :worker/run-id (:worker/run-id binding)
                          :worker/task-id (:worker/task-id binding)}
           :usage/count attempt-count}
    (:config/max-budget-usd config) (assoc :usage/cost-usd (:config/max-budget-usd config))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} grant-refusal
  "Nil when `grant` is the binding's live root spend grant, issued to the
   acting principal, covering the run and task and the configured budget."
  [grant acting binding config now]
  (let [result (grant/authorize grant (spend-usage binding config) now)]
    (cond
      (nil? grant)
      :anomalies.agent-worker/grant-absent

      (not= spend-effect (:grant/effect-class grant))
      :anomalies.agent-worker/grant-wrong-effect

      (not= (:grant/id grant) (:worker/grant-id binding))
      :anomalies.agent-worker/grant-not-bound

      (not= (str (:acting/principal-id acting)) (:grant/principal grant))
      :anomalies.agent-worker/grant-wrong-principal

      (not (grant/authorized? result))
      (get grant-outcome->reason (:grant/outcome result) :anomalies.agent-worker/grant-denied))))
