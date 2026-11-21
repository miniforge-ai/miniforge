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
(ns ai.miniforge.agent-environment.controller
  "The controller's state and ports: the ledger store it keeps leases in,
   its clock, the sink every receipt goes to, and the store artifacts
   persist to. A refusal becomes an anomaly only after its receipt is
   emitted. A ledger change returns `{:ledger/before :ledger/after}` or the
   ledger's anomaly, so no caller acts on a change the store did not take."
  (:require
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.ledger :as ledger]
   [ai.miniforge.agent-environment.receipt :as receipt]
   [ai.miniforge.agent-environment.refusal :as refusal]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create
  [{:keys [provider clock emit! persist! ledger claim-ttl-ms] :or {claim-ttl-ms lease/default-claim-ttl-ms}}]
  {:controller/id (random-uuid)
   :controller/provider provider
   :controller/clock clock
   :controller/emit! emit!
   :controller/persist! persist!
   :controller/ledger ledger
   :controller/claim-ttl-ms claim-ttl-ms})

(defn ^{:stratum 0} change-entry!
  "Apply `f` to key `k`'s entry by compare-and-set: the ledger's change or anomaly."
  [controller k f]
  (ledger/change! (:controller/ledger controller) k f))

(defn ^{:stratum 0} entry
  [controller k]
  (ledger/entry (:controller/ledger controller) k))

(defn ^{:stratum 0} target
  "The provider target an entry names; before an environment exists, its
   namespace is the one derived from the request."
  [entry]
  {:target/namespace (or (get-in entry [:entry/environment :environment/namespace])
                         (lease/namespace-name (:entry/request entry)))
   :target/uid (get-in entry [:entry/environment :environment/uid])
   :target/generation (:entry/generation entry)})

(defn- ^{:stratum 0} claimed
  "The entry a claim leaves: a reservation under `claim` when `request`'s
   decision against `existing` is `:new`, else `existing`."
  [request claim existing]
  (if (= :new (:decision/kind (lease/acquire-decision existing request)))
    (lease/reserve existing request claim)
    existing))

(defn- ^{:stratum 0} settled
  "Apply `f` only while `lease` is current and the entry is not released:
   a release, once confirmed, is never undone by a late result."
  [lease f existing]
  (if (and (= lease (lease/current-lease existing)) (not= :state/released (:entry/state existing)))
    (f existing)
    existing))

(defn- ^{:stratum 0} reserved-settled
  "Record a provision outcome only for the reservation it answers, while
   that reservation is still provisioning; its request digest is kept."
  [generation outcome existing]
  (if (and (= generation (:entry/generation existing))
           (= :state/provisioning (:entry/state existing)))
    (merge existing outcome)
    existing))

(defn- ^{:stratum 0} decided
  "The acquire decision a claim change made, with the reserved generation
   when `:new` and the environment the decision saw when `:duplicate`
   (never a later entry for the same key)."
  [request {:ledger/keys [before after]}]
  (let [decision (lease/acquire-decision before request)]
    (case (:decision/kind decision)
      :new (assoc decision :decision/generation (:entry/generation after))
      :duplicate (assoc decision :decision/environment (:entry/environment before))
      decision)))

(defn- ^{:stratum 0} failure-reason
  "A provider anomaly's subtype, or its type when it carries none."
  [result]
  (or (anomaly/subtype result) (:anomaly/type result)))

(defn- ^{:stratum 0} claim-for
  [controller now]
  (lease/claim (:controller/id controller)
               (.plusMillis (lease/->instant now) (:controller/claim-ttl-ms controller))))

(defn ^{:stratum 0} record!
  [controller subject operation outcome steps reason]
  (let [at ((:controller/clock controller))
        emitted (receipt/receipt subject operation outcome steps reason at)]
    ((:controller/emit! controller) emitted)
    emitted))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} refuse!
  [controller subject operation reason steps]
  (let [refused (record! controller subject operation :outcome/refused steps reason)]
    (refusal/reason-anomaly reason {:receipt/id (:receipt/id refused)})))

(defn ^{:stratum 1} refuse-anomaly!
  "Receipt ledger anomaly `result` as a refusal; return it with the receipt id."
  [controller subject operation result]
  (let [refused (record! controller subject operation :outcome/refused [] (anomaly/subtype result))]
    (assoc-in result [:anomaly/data :receipt/id] (:receipt/id refused))))

(defn ^{:stratum 1} finish!
  "Receipt a provider call's `{:call/value :call/steps}` or anomaly and
   return the value or the anomaly. A call whose provider steps ran but
   whose ledger record failed also carries `:call/reason`, so its receipt
   says what applied and why the ledger does not show it."
  [controller subject operation result]
  (if (anomaly/anomaly? result)
    (do (record! controller subject operation (refusal/result-outcome result) [] (failure-reason result))
        result)
    (do (record! controller subject operation (receipt/steps-outcome (:call/steps result)) (:call/steps result)
                 (:call/reason result))
        (:call/value result))))

(defn ^{:stratum 1} claim!
  "Apply `request`'s acquire decision to the ledger by compare-and-set and
   return it, or the ledger's anomaly: no decision without a stored claim.
   A new reservation carries this controller's claim until its TTL ends."
  [controller request]
  (let [now ((:controller/clock controller))
        claim (claim-for controller now)]
    (anomaly/let-ok [changed (ledger/change! (:controller/ledger controller)
                                             (lease/request-key request)
                                             (partial claimed request claim))]
      (decided request changed))))

(defn ^{:stratum 1} settle!
  "Apply `f` to the entry `lease` names, only while it is still current:
   a stale holder can never move a replacement environment."
  [controller lease f]
  (ledger/change! (:controller/ledger controller) (lease/lease-key lease) (partial settled lease f)))

(defn ^{:stratum 1} settle-provision!
  "Record the provision outcome for the generation `claim!` reserved."
  [controller request generation state environment]
  (let [outcome {:entry/state state
                 :entry/claim nil
                 :entry/environment environment}]
    (ledger/change! (:controller/ledger controller)
                    (lease/request-key request)
                    (partial reserved-settled generation outcome))))
