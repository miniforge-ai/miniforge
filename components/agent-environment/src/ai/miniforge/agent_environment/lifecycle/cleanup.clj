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
(ns ai.miniforge.agent-environment.lifecycle.cleanup
  "Deleting an environment through the ledger. The intent (the UID and
   generation to delete, and when it was requested) is written by
   compare-and-set before any delete, and only the caller whose intent the
   ledger took sends it. Its outcome is recorded against that intent:
   applied when the provider confirms the deletes, verified when the target
   no longer shows the UID. Only a verified cleanup releases the entry."
  (:require
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.lifecycle.support :as support]
   [ai.miniforge.agent-environment.receipt :as receipt]
   [ai.miniforge.agent-environment.refusal :as refusal]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} intent
  "What a cleanup deletes and which attempt owns it. The attempt id keeps
   two attempts at one instant apart, so ownership and outcomes never cross."
  [attempt uid generation requested-at]
  {:cleanup/attempt attempt
   :cleanup/uid uid
   :cleanup/generation generation
   :cleanup/requested-at requested-at
   :cleanup/applied? false
   :cleanup/verified? false})

(defn- ^{:stratum 0} requested-under-lease
  "`entry` releasing under `cleanup`, unless it no longer admits a release under `lease`."
  [lease now cleanup entry]
  (if (lease/lease-refusal entry :environment/release lease now)
    entry
    (assoc entry :entry/state :state/releasing :entry/cleanup cleanup)))

(defn- ^{:stratum 0} releasing
  [entry environment cleanup claim]
  (assoc entry :entry/state :state/releasing :entry/environment environment :entry/cleanup cleanup :entry/claim claim))

(defn- ^{:stratum 0} abandoned?
  "True for a reservation a reconcile released with no environment."
  [entry]
  (and (= :state/released (:entry/state entry)) (nil? (:entry/environment entry))))

(defn- ^{:stratum 0} outcome-recorded
  "The entry once `cleanup`'s deletes ran: released when they applied and
   the UID is verified gone, else cleanup-uncertain to retry. Unchanged when
   it no longer holds `cleanup`, as when a later release replaced it."
  [cleanup applied? verified? entry]
  (let [state (if (and applied? verified?) :state/released :state/cleanup-uncertain)
        recorded (assoc cleanup :cleanup/applied? applied? :cleanup/verified? verified?)]
    (if (= cleanup (:entry/cleanup entry))
      (assoc entry :entry/state state :entry/cleanup recorded)
      entry)))

(defn- ^{:stratum 0} superseded
  "Nil when `recorded` holds this attempt's outcome or left the entry
   released, else the entry's refusal: another attempt replaced this
   intent before its outcome, so the release is not done."
  [cleanup recorded]
  (let [after (:ledger/after recorded)
        own? (= (:cleanup/attempt cleanup) (get-in after [:entry/cleanup :cleanup/attempt]))
        reason (get lease/state->refusal (:entry/state after) :anomalies.agent-environment/ledger-conflict)]
    (when-not (or (anomaly/anomaly? recorded) own? (= :state/released (:entry/state after)))
      (refusal/reason-anomaly reason {}))))

(defn- ^{:stratum 0} owns?
  [cleanup changed]
  (= cleanup (get-in changed [:ledger/after :entry/cleanup])))

(defn- ^{:stratum 0} gone?
  "True when observing `target` no longer finds `uid`. An observation that
   fails verifies nothing."
  [c target uid]
  (let [seen (protocol/observe (support/provider c) target)]
    (and (not (anomaly/anomaly? seen)) (not= uid (:observed/uid seen)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} for-reservation
  "Ownership for what a reservation's own provision created: the
   reservation at `generation` while still provisioning, or after a
   reconcile released it with no environment. Never an adopted environment
   or a newer generation."
  [generation environment cleanup claim entry]
  (if (and (= generation (:entry/generation entry))
           (or (= :state/provisioning (:entry/state entry)) (abandoned? entry)))
    (releasing entry environment cleanup claim)
    entry))

(defn ^{:stratum 1} for-stale
  "Ownership for a reconcile's cleanup: `stale`, while still the key's exact entry."
  [stale environment cleanup claim entry]
  (if (= stale entry) (releasing entry environment cleanup claim) entry))

(defn- ^{:stratum 1} unowned
  "Nil when the change recorded `cleanup`, else why it did not."
  [cleanup changed lease now]
  (when-not (owns? cleanup changed)
    (refusal/reason-anomaly (or (lease/lease-refusal (:ledger/after changed) :environment/release lease now)
                                :anomalies.agent-environment/environment-releasing)
                            {})))

(defn- ^{:stratum 1} deleted
  "Send `cleanup`'s deletes at `target` and verify them: the steps, with
   whether the deletes applied and whether the UID is gone."
  [c target cleanup]
  (let [uid (:cleanup/uid cleanup)
        at (assoc target :target/uid uid)
        steps (support/reported-steps (protocol/deprovision! (support/provider c) at) :deprovision/steps)
        verified? (gone? c at uid)
        verify-outcome (if verified? :outcome/applied :outcome/uncertain)]
    {:steps (conj steps {:step/name :step/verified :step/outcome verify-outcome})
     :applied? (= :outcome/applied (receipt/steps-outcome steps))
     :verified? verified?}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} released!
  "Release `lease`'s environment at `target` under a cleanup intent. The
   call result for its receipt, applied only when the deletes are verified;
   or, when the ledger did not take the intent, its anomaly, and nothing
   was deleted."
  [c lease target]
  (let [now (support/now c)
        cleanup (intent (random-uuid) (:lease/environment-uid lease) (:lease/generation lease) now)]
    (anomaly/let-ok [changed (controller/settle! c lease (partial requested-under-lease lease now cleanup))
                     _ (unowned cleanup changed lease now)]
      (let [{:keys [steps applied? verified?]} (deleted c target cleanup)
            recorded (controller/settle! c lease (partial outcome-recorded cleanup applied? verified?))
            settled (or (superseded cleanup recorded) recorded)]
        (support/recorded-call settled {:call/outcome (receipt/steps-outcome steps)} steps)))))

(defn ^{:stratum 2} cleaned-under!
  "Delete `environment` under a cleanup intent `requested` records on its
   key: `(requested environment cleanup claim entry)` returns the entry
   releasing it when the caller owns the cleanup, else the entry unchanged.
   The entry carries this controller's claim while the cleanup is pending;
   past it, an acquire reconciles the entry, so an interrupted cleanup no
   lease holder can retry does not hold the key. Returns
   `{:cleanup/receipt r :cleanup/recorded change}`; when the intent was not
   taken, nothing is deleted and the receipt is nil."
  [c subject request target environment requested reason]
  (let [k (lease/request-key request)
        now (support/now c)
        cleanup (intent (random-uuid) (:environment/uid environment) (:target/generation target) now)
        claim (controller/claim-for c now)
        changed (controller/change-entry! c k (partial requested environment cleanup claim))]
    (if (or (anomaly/anomaly? changed) (not (owns? cleanup changed)))
      {:cleanup/receipt nil :cleanup/recorded changed}
      (let [{:keys [steps applied? verified?]} (deleted c target cleanup)
            recorded (controller/change-entry! c k (partial outcome-recorded cleanup applied? verified?))
            outcome (receipt/steps-outcome steps)
            cleaned (controller/record! c subject :environment/release outcome steps reason)]
        {:cleanup/receipt cleaned :cleanup/recorded recorded}))))
