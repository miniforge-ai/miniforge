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
(ns ai.miniforge.agent-environment.lifecycle.provisioning
  "Provisioning a claimed generation: a complete provision becomes the
   key's environment; one that stops part way is receipted, cleaned up at
   once, and left `cleanup-uncertain` when the cleanup is not confirmed. An
   object whose ownership is not shown is never deleted by name."
  (:require
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.lifecycle.cleanup :as cleanup]
   [ai.miniforge.agent-environment.lifecycle.support :as support]
   [ai.miniforge.agent-environment.receipt :as receipt]
   [ai.miniforge.agent-environment.refusal :as refusal]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} maybe-created
  "Step outcomes after which something may exist to recover."
  #{:outcome/applied :outcome/uncertain})

(defn- ^{:stratum 0} uncleaned!
  "Record a failed provision nothing is deleted for: released when every
   step failed, so nothing exists, else cleanup-uncertain."
  [c request target environment outcome]
  (let [state (if (= :outcome/failed outcome) :state/released :state/cleanup-uncertain)]
    (controller/settle-provision! c request (:target/generation target) state environment)))

(defn- ^{:stratum 0} late-reason
  "Why a recorded provision is not handed out: another controller adopted
   it and has since moved it on (its state's refusal), or reconciled the
   reservation away after the claim expired (`claim-lost`). Nil when the
   entry holds `env`'s lease as a provisioned environment."
  [recorded env]
  (let [after (:ledger/after recorded)]
    (cond
      (not= (:environment/lease env) (lease/current-lease after)) :anomalies.agent-environment/claim-lost
      (not= :state/provisioned (:entry/state after)) (get lease/state->refusal (:entry/state after))
      :else nil)))

(defn- ^{:stratum 0} reservation-cleaned!
  [c subject request target environment]
  (cleanup/cleaned-under! c subject request target environment
                          (partial cleanup/for-reservation (:target/generation target))
                          :anomalies.agent-environment/provisioning-failed))

(defn- ^{:stratum 0} claim-lost!
  "Delete what a late owner created, under a cleanup intent on the
   reservation the reconcile released, and refuse with `claim-lost`. When a
   newer generation already holds the key, the intent is not taken and
   nothing is deleted here; the UID in the refusal is left for orphan
   discovery."
  [c subject request target env]
  (let [cleaned (cleanup/cleaned-under! c subject request target env
                                        (partial cleanup/for-abandoned (:target/generation target))
                                        :anomalies.agent-environment/claim-lost)
        receipt-ids (filterv some? [(get-in cleaned [:cleanup/receipt :receipt/id])])]
    (refusal/reason-anomaly :anomalies.agent-environment/claim-lost
                            {:receipt/ids receipt-ids :environment/uid (:environment/uid env)})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} recovered-uid
  "A provider that applied (or may have applied) steps but reported no UID
   is asked for it, so the environment still gets a lease. The UID is
   adopted only from an object carrying this generation, so one recreated
   under the name is not ours; nil when nothing may exist or ownership is
   not shown."
  [c target steps]
  (when (some #(contains? maybe-created (:step/outcome %)) steps)
    (let [observed (protocol/observe (support/provider c) target)]
      (when (= (:target/generation target) (:observed/generation observed))
        (:observed/uid observed)))))

(defn- ^{:stratum 1} provision-failed
  [c acting request target environment steps]
  (let [subject (support/subject-of acting request environment)
        outcome (support/unconfirmed-if-anonymous (receipt/steps-outcome steps) environment)
        acquired (controller/record! c subject :environment/acquire outcome steps
                                     :anomalies.agent-environment/provisioning-failed)
        cleanup? (and environment (not= :outcome/failed outcome))
        cleaned (when cleanup? (reservation-cleaned! c subject request target environment))
        recorded (if cleanup? (:cleanup/recorded cleaned) (uncleaned! c request target environment outcome))
        receipt-ids (filterv some? [(:receipt/id acquired) (get-in cleaned [:cleanup/receipt :receipt/id])])
        known {:receipt/ids receipt-ids :environment/lease (:environment/lease environment)}]
    (if (anomaly/anomaly? recorded)
      (update recorded :anomaly/data merge known)
      (refusal/reason-anomaly :anomalies.agent-environment/provisioning-failed known))))

(defn- ^{:stratum 1} adopted
  "Record a complete provision as the key's environment and return it. If
   the ledger does not take it, no lease is handed out: the reservation
   stays provisioning, an unknown outcome, and the caller gets the anomaly.
   If another controller adopted it and moved it on, the caller gets that
   state's refusal. If another controller reconciled the reservation away,
   what this call created is deleted under a cleanup intent and the caller
   gets `claim-lost`."
  [c acting request target env steps]
  (let [recorded (controller/settle-provision! c request (:target/generation target) :state/provisioned env)
        reason (or (support/ledger-reason recorded) (late-reason recorded env))
        subject (support/subject-of acting request env)]
    (controller/record! c subject :environment/acquire :outcome/applied steps reason)
    (cond
      (nil? reason) env
      (anomaly/anomaly? recorded) recorded
      (not= :anomalies.agent-environment/claim-lost reason) (refusal/reason-anomaly reason {})
      :else (claim-lost! c subject request target env))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} provision
  [c acting request generation]
  (let [target {:target/namespace (lease/namespace-name request)
                :target/uid nil
                :target/generation generation}
        provisioned (protocol/provision! (support/provider c) target request)
        steps (support/reported-steps provisioned :provision/steps)
        env (support/environment request target (or (:provision/uid provisioned) (recovered-uid c target steps)))]
    (if (and env (= :outcome/applied (receipt/steps-outcome steps)))
      (adopted c acting request target env steps)
      (provision-failed c acting request target env steps))))
