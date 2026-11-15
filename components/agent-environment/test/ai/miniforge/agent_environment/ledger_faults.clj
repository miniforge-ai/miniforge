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
(ns ai.miniforge.agent-environment.ledger-faults
  "Fault injection for lifecycle tests under ledger outages: a fake
   provider that counts every call, and a ledger store whose reads and
   writes can be switched off."
  (:require
   [ai.miniforge.agent-environment.conformance.fixtures :as fx]
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-environment.interface.ledger :as store]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.agent-environment.ledger.memory :as memory-ledger]
   [ai.miniforge.anomaly.interface :as anomaly])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} down
  (anomaly/sub-anomaly :unavailable :anomalies.agent-environment/ledger-unavailable "down" {}))

(defn- ^{:stratum 0} tally! [calls op] (swap! calls update op (fnil inc 0)))

(defn- ^{:stratum 0} run-interposed!
  "Run, once, the `:before-write` fn a test left in `faults`: another
   controller's write landing between this read and this write."
  [faults]
  (when-let [interpose (:before-write (first (swap-vals! faults dissoc :before-write)))]
    (interpose)))

(defn- ^{:stratum 0} writable!
  "True while writes remain, spending one: nil `:writes` never runs out."
  [faults]
  (let [[before _] (swap-vals! faults update :writes #(some-> % dec))
        left (:writes before)]
    (or (nil? left) (pos? left))))

(defn ^{:stratum 0} controller-over
  "Another controller over `w`'s provider and receipt log, keeping leases
   in `ledger-store`: a restarted or concurrent controller process."
  [w ledger-store]
  (env/create-controller {:provider (get-in w [:c :controller/provider])
                          :clock #(Instant/now)
                          :emit! #(swap! (:receipts w) conj %)
                          :persist! fx/memory-persister
                          :ledger ledger-store}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} counting
  "`inner` with every provider call counted by operation in `calls`."
  [inner calls]
  (reify protocol/EnvironmentProvider
    (capabilities [_] (protocol/capabilities inner))
    (probe [_ request] (tally! calls :probe) (protocol/probe inner request))
    (provision! [_ target request] (tally! calls :provision!) (protocol/provision! inner target request))
    (observe [_ target] (tally! calls :observe) (protocol/observe inner target))
    (exec! [_ target command] (tally! calls :exec!) (protocol/exec! inner target command))
    (write-file! [_ target path text] (tally! calls :write-file!) (protocol/write-file! inner target path text))
    (read-file [_ target path] (tally! calls :read-file) (protocol/read-file inner target path))
    (cancel! [_ target] (tally! calls :cancel!) (protocol/cancel! inner target))
    (deprovision! [_ target] (tally! calls :deprovision!) (protocol/deprovision! inner target))))

(defn ^{:stratum 1} flaky
  "`inner` with reads failing while `:reads?` is false, writes failing once
   `:writes` is spent, and a one-shot `:before-write` fn run ahead of the
   next write."
  [inner faults]
  (reify store/LedgerStore
    (read-entry [_ k] (if (:reads? @faults) (store/read-entry inner k) down))
    (write-entry! [_ k revision entry]
      (run-interposed! faults)
      (if (writable! faults) (store/write-entry! inner k revision entry) down))
    (entries [_] (store/entries inner))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} outage-world
  "A conformance world over a counted fake provider and a flaky memory
   ledger, plus `:fake`, `:calls`, `:inner` (the store underneath) and
   `:faults` (the switch: `:reads?` and `:writes` left, nil for no limit)."
  []
  (let [inner (memory-ledger/create)
        faults (atom {:reads? true :writes nil})
        fake (env/fake-provider)
        calls (atom {})
        w (fx/world (counting fake calls) :isolation/shared-kernel (flaky inner faults))]
    (assoc w :inner inner :faults faults :fake fake :calls calls)))
