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
(ns ai.miniforge.agent-environment.admission
  "Whether a request may be admitted: the acting context must be the tenant
   and principal it names, a live provision grant must cover its run and
   task, and the provider must declare the isolation and limits it asks for.
   Each check returns nil or the refusal reason."
  (:require
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.execution-grant.interface :as grant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} provision-effect
  "The grant effect class that authorizes provisioning an environment."
  :effect/provision)

(def ^{:stratum 0} provision-count
  "The count one acquire presents; `authorize` compares it to the grant's
   count ceiling. Nothing here tracks a cumulative count."
  1)

(def ^{:stratum 0} grant-outcome->reason
  "Refusal reason for each non-authorized `grant/authorize` outcome."
  {:absent :anomalies.agent-environment/grant-absent
   :inactive :anomalies.agent-environment/grant-inactive
   :scope-mismatch :anomalies.agent-environment/grant-scope-mismatch
   :exceeded :anomalies.agent-environment/grant-exceeded})

(defn ^{:stratum 0} tenant-refusal
  [acting tenant-id]
  (when (not= (:acting/tenant-id acting) tenant-id)
    :anomalies.agent-environment/forged-tenant))

(defn- ^{:stratum 0} limits-exceeded?
  [ceiling limits]
  (boolean (some (fn [[axis value]] (> value (get ceiling axis))) limits)))

(defn ^{:stratum 0} expiry-refusal
  [request now]
  (when (lease/expired? request now)
    :anomalies.agent-environment/request-expired))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} provision-usage
  "The usage an acquire presents: its run and task as the effect scope."
  [request]
  {:effect/scope (select-keys request [:environment/tenant-id
                                       :environment/run-id
                                       :environment/task-id])
   :usage/count provision-count})

(defn ^{:stratum 1} identity-refusal
  [acting request]
  (or (tenant-refusal acting (:environment/tenant-id request))
      (when (not= (:acting/principal-id acting) (:environment/principal-id request))
        :anomalies.agent-environment/forged-principal)))

(defn ^{:stratum 1} capability-failures
  "Preflight failures the provider's declared capabilities predict."
  [capabilities request]
  (cond-> []
    (not (contains? (:capabilities/isolation capabilities) (:environment/isolation request)))
    (conj :anomalies.agent-environment/isolation-unavailable)

    (limits-exceeded? (:capabilities/max-limits capabilities) (:environment/limits request))
    (conj :anomalies.agent-environment/limits-exceeded)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} grant-refusal
  "Nil when `grant` authorizes `acting` to provision for `request` at `now`.
   Delegated grants are refused: no ancestor lookup is supplied."
  [grant acting request now]
  (let [result (grant/authorize grant (provision-usage request) now)]
    (cond
      (nil? grant)
      :anomalies.agent-environment/grant-absent

      (not= provision-effect (:grant/effect-class grant))
      :anomalies.agent-environment/grant-wrong-effect

      (not= (str (:acting/principal-id acting)) (:grant/principal grant))
      :anomalies.agent-environment/grant-wrong-principal

      (not (grant/authorized? result))
      (get grant-outcome->reason (:grant/outcome result) :anomalies.agent-environment/grant-denied))))
