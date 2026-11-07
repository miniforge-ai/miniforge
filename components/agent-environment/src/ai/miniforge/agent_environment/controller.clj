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
  "The controller's state and ports: the lease ledger it owns, its clock,
   the sink every receipt goes to, and the store artifacts persist to.
   A refusal becomes an anomaly only after its receipt is emitted."
  (:require
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.receipt :as receipt]
   [ai.miniforge.agent-environment.refusal :as refusal]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create
  [{:keys [provider clock emit! persist!]}]
  {:controller/provider provider
   :controller/clock clock
   :controller/emit! emit!
   :controller/persist! persist!
   :controller/ledger (atom {})})

(defn ^{:stratum 0} entry
  [controller k]
  (get @(:controller/ledger controller) k))

(defn ^{:stratum 0} target
  [entry]
  {:target/namespace (get-in entry [:entry/environment :environment/namespace])
   :target/uid (get-in entry [:entry/environment :environment/uid])
   :target/generation (:entry/generation entry)})

(defn- ^{:stratum 0} claim-entry
  [ledger k request]
  (let [existing (get ledger k)]
    (if (= :new (:decision/kind (lease/acquire-decision existing request)))
      (assoc ledger k (lease/reserve existing request))
      ledger)))

(defn- ^{:stratum 0} settle-entry
  [ledger k lease f]
  (if (= lease (lease/current-lease (get ledger k)))
    (update ledger k f)
    ledger))

(defn- ^{:stratum 0} settle-reserved
  [ledger k generation entry]
  (if (= generation (get-in ledger [k :entry/generation]))
    (assoc ledger k entry)
    ledger))

(defn- ^{:stratum 0} failure-reason
  "A provider anomaly's subtype, or its type when it carries none."
  [result]
  (or (anomaly/subtype result) (:anomaly/type result)))

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

(defn ^{:stratum 1} finish!
  "Receipt a provider call's `{:call/value :call/steps}` or anomaly and
   return the value or the anomaly."
  [controller subject operation result]
  (if (anomaly/anomaly? result)
    (do (record! controller subject operation (refusal/result-outcome result) [] (failure-reason result))
        result)
    (do (record! controller subject operation (receipt/steps-outcome (:call/steps result)) (:call/steps result) nil)
        (:call/value result))))

(defn ^{:stratum 1} claim!
  "Apply `request`'s acquire decision to the ledger atomically and return
   it, carrying the reserved generation when it is `:new`."
  [controller request]
  (let [k (lease/request-key request)
        [before after] (swap-vals! (:controller/ledger controller) claim-entry k request)
        decision (lease/acquire-decision (get before k) request)
        generation (get-in after [k :entry/generation])]
    (cond-> decision
      (= :new (:decision/kind decision)) (assoc :decision/generation generation))))

(defn ^{:stratum 1} settle!
  "Apply `f` to the entry `lease` names, only while it is still current:
   a stale holder can never move a replacement environment."
  [controller lease f]
  (swap! (:controller/ledger controller) settle-entry (lease/lease-key lease) lease f))

(defn ^{:stratum 1} settle-provision!
  "Record the provision outcome for the generation `claim!` reserved."
  [controller request generation state environment]
  (let [entry {:entry/state state
               :entry/generation generation
               :entry/request request
               :entry/environment environment}]
    (swap! (:controller/ledger controller) settle-reserved (lease/request-key request) generation entry)))
