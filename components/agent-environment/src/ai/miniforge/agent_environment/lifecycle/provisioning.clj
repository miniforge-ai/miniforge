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

(defn- ^{:stratum 0} adopted
  "Record a complete provision as the key's environment and return it. If
   the ledger does not take it, no lease is handed out: the reservation
   stays provisioning, an unknown outcome, and the caller gets the anomaly."
  [c acting request generation env steps]
  (let [recorded (controller/settle-provision! c request generation :state/provisioned env)
        reason (support/ledger-reason recorded)]
    (controller/record! c (support/subject-of acting request env) :environment/acquire :outcome/applied steps reason)
    (if reason recorded env)))

(defn- ^{:stratum 0} uncleaned!
  "Record a failed provision nothing is deleted for: released when every
   step failed, so nothing exists, else cleanup-uncertain."
  [c request target environment outcome]
  (let [state (if (= :outcome/failed outcome) :state/released :state/cleanup-uncertain)]
    (controller/settle-provision! c request (:target/generation target) state environment)))

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
        cleaned (when cleanup? (cleanup/reservation-cleaned! c subject request target environment))
        recorded (if cleanup? (:cleanup/recorded cleaned) (uncleaned! c request target environment outcome))
        receipt-ids (filterv some? [(:receipt/id acquired) (get-in cleaned [:cleanup/receipt :receipt/id])])]
    (if (anomaly/anomaly? recorded)
      recorded
      (refusal/reason-anomaly :anomalies.agent-environment/provisioning-failed
                              {:receipt/ids receipt-ids
                               :environment/lease (:environment/lease environment)}))))

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
      (adopted c acting request generation env steps)
      (provision-failed c acting request target env steps))))
