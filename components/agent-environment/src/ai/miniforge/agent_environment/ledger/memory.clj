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
(ns ai.miniforge.agent-environment.ledger.memory
  "A ledger store held in one atom. It keeps the compare-and-set and
   revision guarantees, but only for its process's lifetime: it is for
   tests and fake-backed runs, never a controller that must survive a
   restart."
  (:require
   [ai.miniforge.agent-environment.interface.ledger :as ledger]
   [ai.miniforge.agent-environment.refusal :as refusal]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} absent
  "What a read of a key with no entry returns."
  {:ledger/entry nil :ledger/revision nil})

(defn- ^{:stratum 0} written
  "The stored map after writing `entry` under `k`; unchanged when `revision`
   is no longer the key's."
  [stored k revision entry]
  (if (= revision (get-in stored [k :ledger/revision]))
    (assoc stored k {:ledger/entry entry :ledger/revision ((fnil inc 0) revision)})
    stored))

(defn- ^{:stratum 0} listed
  [[k stored]]
  (assoc stored :ledger/key k))

;------------------------------------------------------------------------------ Layer 1

(defrecord ^{:stratum 1} MemoryLedger [state]
  ledger/LedgerStore
  (read-entry [_ k]
    (get @state k absent))
  (write-entry! [_ k revision entry]
    (let [[before after] (swap-vals! state written k revision entry)]
      (if (= revision (get-in before [k :ledger/revision]))
        {:ledger/revision (get-in after [k :ledger/revision])}
        (refusal/reason-anomaly :anomalies.agent-environment/ledger-conflict {:ledger/key k}))))
  (entries [_]
    (mapv listed @state)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create
  []
  (->MemoryLedger (atom {})))
