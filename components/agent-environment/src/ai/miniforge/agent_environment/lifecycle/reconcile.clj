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
(ns ai.miniforge.agent-environment.lifecycle.reconcile
  "Reconciling a reservation whose controller's claim expired, before any
   new submission for its key: the provider is asked what it holds for the
   reservation's generation, and the ledger adopts that environment or
   releases the reservation. A provider that cannot answer leaves it as is."
  (:require
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.agent-environment.lifecycle.support :as support]
   [ai.miniforge.agent-environment.receipt :as receipt]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} adoptable
  "The environment `seen` shows for `stale`'s generation, or nil: an object
   carrying another generation, or none, is not this reservation's."
  [stale target seen]
  (when (and (:observed/uid seen) (= (:entry/generation stale) (:observed/generation seen)))
    (support/environment (:entry/request stale) target (:observed/uid seen))))

(defn- ^{:stratum 0} reconcile-call
  "The call result for a reconcile's ledger change: no step when another
   controller reconciled first, else whether it adopted or abandoned."
  [environment changed]
  (let [step-name (if environment :step/adopted :step/abandoned)
        steps (if (= (:ledger/before changed) (:ledger/after changed)) [] [(support/applied-step step-name)])]
    (if (anomaly/anomaly? changed) changed {:call/value changed :call/steps steps})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} settled!
  [c subject stale target seen]
  (if (anomaly/anomaly? seen)
    (controller/finish! c subject :environment/reconcile seen)
    (let [environment (adoptable stale target seen)]
      (controller/finish! c subject :environment/reconcile
                          (reconcile-call environment (controller/settle-reconciled! c stale environment))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} reconcile!
  "Settle the stale reservation a `:reconcile` decision carries, then claim
   `request` again: the fresh decision, or the provider's or the ledger's
   anomaly when either could not answer."
  [c acting request decision]
  (let [stale (:decision/entry decision)
        target (controller/target stale)
        subject (receipt/request-subject acting request)
        seen (protocol/observe (support/provider c) target)]
    (anomaly/let-ok [_ (settled! c subject stale target seen)]
      (controller/claim! c request))))
