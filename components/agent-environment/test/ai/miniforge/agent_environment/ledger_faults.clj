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
  "Fault injection for lifecycle tests: a fake provider that counts its calls
   and a ledger store whose reads and writes can be switched off."
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

(defn- ^{:stratum 0} intercepted
  "Count a provider call by `op` in `calls`, then answer it with the hook a
   test left for `op` in `hooks` when that returns a value, else `delegate`."
  [calls hooks op delegate]
  (swap! calls update op (fnil inc 0))
  (or (when-let [hook (get @hooks op)] (hook)) (delegate)))

(defn- ^{:stratum 0} run-interposed!
  [faults]
  (when-let [interpose (:before-write (first (swap-vals! faults dissoc :before-write)))]
    (interpose)))

(defn- ^{:stratum 0} writable!
  [faults]
  (let [[before _] (swap-vals! faults update :writes #(some-> % dec))
        left (:writes before)]
    (or (nil? left) (pos? left))))

(defn ^{:stratum 0} controller-over
  "Another controller over `w`'s provider and receipt log, keeping leases
   in `ledger-store`, on `clock` when given: a restarted or concurrent
   controller process."
  [w ledger-store & [clock]]
  (env/create-controller {:provider (get-in w [:c :controller/provider])
                          :clock (or clock #(Instant/now))
                          :emit! #(swap! (:receipts w) conj %)
                          :persist! fx/memory-persister
                          :ledger ledger-store}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} counting
  "Every call counted in `calls`; a non-nil value from a `hooks` fn answers in the provider's place."
  [inner calls hooks]
  (reify protocol/EnvironmentProvider
    (capabilities [_] (protocol/capabilities inner))
    (probe [_ request] (intercepted calls hooks :probe #(protocol/probe inner request)))
    (provision! [_ target request] (intercepted calls hooks :provision! #(protocol/provision! inner target request)))
    (observe [_ target] (intercepted calls hooks :observe #(protocol/observe inner target)))
    (exec! [_ target command] (intercepted calls hooks :exec! #(protocol/exec! inner target command)))
    (write-file! [_ target path text] (intercepted calls hooks :write-file! #(protocol/write-file! inner target path text)))
    (read-file [_ target path] (intercepted calls hooks :read-file #(protocol/read-file inner target path)))
    (cancel! [_ target] (intercepted calls hooks :cancel! #(protocol/cancel! inner target)))
    (deprovision! [_ target] (intercepted calls hooks :deprovision! #(protocol/deprovision! inner target)))))

(defn ^{:stratum 1} flaky
  "`inner` failing reads while `:reads?` is false and writes once `:writes` is spent."
  [inner faults]
  (reify store/LedgerStore
    (read-entry [_ k] (swap! faults update :read-count (fnil inc 0)) (if (:reads? @faults) (store/read-entry inner k) down))
    (write-entry! [_ k revision entry]
      (run-interposed! faults)
      (if (writable! faults) (store/write-entry! inner k revision entry) down))
    (entries [_] (store/entries inner))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} outage-world
  "A world over a counted fake provider (`:hooks` intercepts calls) and a flaky ledger;
   `:faults` holds `:reads?`, `:writes`, `:before-write`."
  []
  (let [inner (memory-ledger/create)
        faults (atom {:reads? true :writes nil})
        fake (env/fake-provider)
        calls (atom {})
        hooks (atom {})
        w (fx/world (counting fake calls hooks) :isolation/shared-kernel (flaky inner faults))]
    (assoc w :inner inner :faults faults :fake fake :calls calls :hooks hooks)))
