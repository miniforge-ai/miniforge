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
  "Reconciling a stale entry before any new submission for its key: a
   reservation whose claim expired, or a cleanup left uncertain. The
   provider is asked what it holds; a ready environment of the
   reservation's generation is adopted, anything else of the key's is
   deleted under a cleanup intent, and with nothing left the reservation
   is released. A provider that cannot answer leaves the entry as it is."
  (:require
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.lifecycle.cleanup :as cleanup]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.agent-environment.lifecycle.support :as support]
   [ai.miniforge.agent-environment.receipt :as receipt]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} adoptable
  "The environment `seen` shows for a stale reservation, when it can be
   adopted: ready, under the reservation's own generation. A partial
   environment is never adopted as provisioned."
  [stale target seen]
  (when (and (= :state/provisioning (:entry/state stale))
             (:observed/uid seen)
             (:observed/ready? seen)
             (= (:entry/generation stale) (:observed/generation seen)))
    (support/environment (:entry/request stale) target (:observed/uid seen))))

(defn- ^{:stratum 0} doomed
  "The environment a reconcile deletes before the key is reused: the stale
   entry's own, or an object the provider shows under this or an earlier
   generation of the key. Nil when there is nothing of the key's to delete;
   an object with no generation is never the key's."
  [stale target seen]
  (let [own (:entry/environment stale)
        seen-generation (:observed/generation seen)
        earlier? (and (:observed/uid seen) seen-generation (<= seen-generation (:entry/generation stale)))
        at (assoc target :target/generation seen-generation)]
    (cond
      own own
      earlier? (support/environment (:entry/request stale) at (:observed/uid seen))
      :else nil)))

(defn- ^{:stratum 0} reconcile-call
  "The call result for a reconcile's ledger change: no step when another
   controller reconciled first, else whether it adopted or abandoned."
  [environment changed]
  (let [step-name (if environment :step/adopted :step/abandoned)
        steps (if (= (:ledger/before changed) (:ledger/after changed)) [] [(support/applied-step step-name)])]
    (if (anomaly/anomaly? changed) changed {:call/value changed :call/steps steps})))

(defn- ^{:stratum 0} cleaned!
  "Delete `environment` under a cleanup intent on `stale`: the ledger's
   change, or its anomaly when the intent or its outcome was not recorded."
  [c subject request stale environment]
  (let [at (assoc (controller/target stale) :target/generation (get-in environment [:environment/lease :lease/generation]))]
    (:cleanup/recorded (cleanup/cleaned-under! c subject request at environment (partial cleanup/for-stale stale) nil))))

(defn- ^{:stratum 0} leftover-call
  "The call result for a reconcile that deleted what the key left behind:
   abandoned once the cleanup is verified and recorded, else uncertain."
  [recorded]
  (let [released? (= :state/released (get-in recorded [:ledger/after :entry/state]))
        step {:step/name :step/abandoned :step/outcome (if released? :outcome/applied :outcome/uncertain)}]
    (if (anomaly/anomaly? recorded) recorded {:call/value recorded :call/steps [step]})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} settled!
  "Adopt the ready environment the reservation made, delete what the key
   left behind, or release the reservation when nothing of the key exists."
  [c subject request stale target seen]
  (let [adopted (when-not (anomaly/anomaly? seen) (adoptable stale target seen))
        leftover (when-not (or (anomaly/anomaly? seen) adopted) (doomed stale target seen))]
    (cond
      (anomaly/anomaly? seen) (controller/finish! c subject :environment/reconcile seen)
      leftover (controller/finish! c subject :environment/reconcile (leftover-call (cleaned! c subject request stale leftover)))
      :else (controller/finish! c subject :environment/reconcile
                                (reconcile-call adopted (controller/settle-reconciled! c stale adopted))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} reconcile!
  "Settle the stale entry a `:reconcile` decision carries, then claim
   `request` again: the fresh decision, or the provider's or the ledger's
   anomaly when either could not answer."
  [c acting request decision]
  (let [stale (:decision/entry decision)
        target (controller/target stale)
        subject (receipt/request-subject acting request)
        seen (protocol/observe (support/provider c) target)]
    (anomaly/let-ok [_ (settled! c subject request stale target seen)]
      (controller/claim! c request))))
