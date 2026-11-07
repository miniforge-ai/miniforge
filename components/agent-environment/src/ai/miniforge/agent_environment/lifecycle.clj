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
(ns ai.miniforge.agent-environment.lifecycle
  "Every provider is driven through this sequence: admit, preflight, claim
   the ledger, provision, then lease-checked calls until release. Each call
   emits one receipt; a failed provision also emits its cleanup receipt."
  (:require
   [ai.miniforge.agent-environment.admission :as admission]
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.lifecycle.provisioning :as provisioning]
   [ai.miniforge.agent-environment.lifecycle.support :as support]
   [ai.miniforge.agent-environment.receipt :as receipt]
   [ai.miniforge.agent-environment.refusal :as refusal]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} duplicate
  [c acting request]
  (let [env (:entry/environment (controller/entry c (lease/request-key request)))]
    (controller/record! c (support/subject-of acting request env) :environment/acquire :outcome/duplicate [] nil)
    env))

(defn- ^{:stratum 0} leased
  [c operation acting lease call]
  (let [subject (receipt/lease-subject acting lease)
        entry (controller/entry c (lease/lease-key lease))
        refusal (or (admission/tenant-refusal acting (:lease/tenant-id lease))
                    (lease/lease-refusal entry operation lease (support/now c)))]
    (if refusal
      (controller/refuse! c subject operation refusal [])
      (controller/finish! c subject operation (call (controller/target entry))))))

(defn- ^{:stratum 0} observed
  [c lease target]
  (let [seen (protocol/observe (support/provider c) target)]
    (if (= (:lease/environment-uid lease) (:observed/uid seen))
      (support/as-call {:readiness/ready? (:observed/ready? seen)})
      (refusal/reason-anomaly :anomalies.agent-environment/uid-mismatch {:observed/uid (:observed/uid seen)}))))

(defn- ^{:stratum 0} persisted
  [c lease target paths]
  (let [stored (mapv #(support/persist-one c lease target %) paths)]
    (or (first (filter anomaly/anomaly? stored))
        {:call/value stored
         :call/steps (mapv (constantly support/persisted-step) stored)})))

(defn- ^{:stratum 0} settled
  "Move the ledger to `state` whatever the provider confirmed: the ledger
   stops admitting work on request, and the receipt says what applied."
  [c lease steps state]
  (controller/settle! c lease #(assoc % :entry/state state))
  {:call/value {:call/outcome (receipt/steps-outcome steps)} :call/steps steps})

(defn ^{:stratum 0} preflight
  [c acting request]
  (let [subject (receipt/request-subject acting request)
        failures (if-let [refused (admission/identity-refusal acting request)]
                   [refused]
                   (support/preflight-failures c request))
        outcome (if (seq failures) :outcome/refused :outcome/applied)
        reason (when (seq failures) :anomalies.agent-environment/preflight-failed)]
    (controller/record! c subject :environment/preflight outcome (mapv support/failure-step failures) reason)
    {:preflight/failures failures}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} released
  [c lease target]
  (let [steps (:deprovision/steps (protocol/deprovision! (support/provider c) target))
        state (if (= :outcome/applied (receipt/steps-outcome steps)) :state/released :state/cleanup-uncertain)]
    (settled c lease steps state)))

(defn ^{:stratum 1} acquire!
  [c acting grant request]
  (let [subject (receipt/request-subject acting request)
        at (support/now c)
        refused (or (admission/identity-refusal acting request)
                    (admission/expiry-refusal request at)
                    (admission/grant-refusal grant acting request at))
        failures (when-not refused (support/preflight-failures c request))
        decision (when-not (or refused (seq failures)) (controller/claim! c request))]
    (cond
      refused (controller/refuse! c subject :environment/acquire refused [])
      (seq failures) (controller/refuse! c subject :environment/acquire
                                         :anomalies.agent-environment/preflight-failed
                                         (mapv support/failure-step failures))
      (= :new (:decision/kind decision)) (provisioning/provision c acting request (:decision/generation decision))
      (= :duplicate (:decision/kind decision)) (duplicate c acting request)
      :else (controller/refuse! c subject :environment/acquire (:decision/reason decision) []))))

(defn ^{:stratum 1} readiness
  [c acting lease]
  (leased c :environment/readiness acting lease #(observed c lease %)))

(defn ^{:stratum 1} exec!
  [c acting lease command]
  (leased c :environment/exec acting lease #(support/as-call (protocol/exec! (support/provider c) % command))))

(defn ^{:stratum 1} write-file!
  [c acting lease path text]
  (leased c :environment/copy acting lease
          #(support/as-call (protocol/write-file! (support/provider c) % path text))))

(defn ^{:stratum 1} read-file
  [c acting lease path]
  (leased c :environment/copy acting lease #(support/as-call (protocol/read-file (support/provider c) % path))))

(defn ^{:stratum 1} persist-artifacts!
  [c acting lease paths]
  (leased c :environment/persist-artifacts acting lease #(persisted c lease % paths)))

(defn ^{:stratum 1} cancel!
  [c acting lease]
  (leased c :environment/cancel acting lease
          #(settled c lease (:cancel/steps (protocol/cancel! (support/provider c) %)) :state/cancelled)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} release!
  [c acting lease]
  (leased c :environment/release acting lease #(released c lease %)))
