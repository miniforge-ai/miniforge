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
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.ledger.memory :as memory-ledger]
   [ai.miniforge.anomaly.interface :as anomaly])
  (:import
   [java.time Duration Instant]))

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
  "Run and clear the one-shot fn a test left under `hook` in `faults`."
  [faults hook]
  (when-let [interpose (get (first (swap-vals! faults dissoc hook)) hook)]
    (interpose)))

(defn- ^{:stratum 0} writable!
  [faults]
  (let [[before _] (swap-vals! faults update :writes #(some-> % dec))
        left (:writes before)]
    (or (nil? left) (pos? left))))

(defn ^{:stratum 0} later
  "A clock reading past every claim made now."
  []
  (.plus (Instant/now) (Duration/ofMillis (inc lease/default-claim-ttl-ms))))

(defn ^{:stratum 0} receipts-of
  [w operation]
  (filterv #(= operation (:receipt/operation %)) @(:receipts w)))

(defn ^{:stratum 0} reacquire [w c req] (env/acquire! c (:a w) (fx/provision-grant w (:a w)) req))

(defn ^{:stratum 0} abandon!
  "Release `req`'s reservation in `w`'s ledger as a rival that reconciled
   it after its claim expired and found nothing would."
  [w req]
  (let [k (lease/request-key req)
        {:ledger/keys [entry revision]} (store/read-entry (:inner w) k)]
    (store/write-entry! (:inner w) k revision (lease/reconciled entry nil))))

(defn ^{:stratum 0} at-record
  "A `provision!` hook that runs `rival!` just before the owner records its
   provision; it returns nil, so the provider still answers."
  [w rival!]
  (fn []
    (swap! (:faults w) assoc :before-write rival!)
    nil))

(defn ^{:stratum 0} controller-over
  "Another controller over `w`'s provider and receipt log, keeping leases
   in `ledger-store`, with `options` (such as `:clock`) over the defaults:
   a restarted or concurrent controller process."
  [w ledger-store & [options]]
  (env/create-controller (merge {:provider (get-in w [:c :controller/provider])
                                 :clock #(Instant/now)
                                 :emit! #(swap! (:receipts w) conj %)
                                 :persist! fx/memory-persister
                                 :ledger ledger-store}
                                options)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} restarted
  "A controller over `w`'s provider and ledger on `clock`: a restarted or
   concurrent controller process."
  [w clock]
  (controller-over w (get-in w [:c :controller/ledger]) {:clock clock}))

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
    (read-entry [_ k]
      (run-interposed! faults :before-read)
      (swap! faults update :read-count (fnil inc 0))
      (if (:reads? @faults) (store/read-entry inner k) down))
    (write-entry! [_ k revision entry]
      (run-interposed! faults :before-write)
      (if (writable! faults) (store/write-entry! inner k revision entry) down))
    (entries [_] (store/entries inner))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} late-controller
  "A controller over `w`'s ledger started after every claim made now expired."
  [w]
  (restarted w later))

(defn ^{:stratum 2} outage-world
  "A world over a counted fake provider (`:hooks` intercepts calls) and a flaky ledger;
   `:faults` holds `:reads?`, `:writes`, and the one-shot `:before-write` and
   `:before-read`. `fake-options` configure the fake."
  ([] (outage-world {}))
  ([fake-options]
   (let [inner (memory-ledger/create)
         faults (atom {:reads? true :writes nil})
         fake (env/fake-provider fake-options)
         calls (atom {})
         hooks (atom {})
         w (fx/world (counting fake calls hooks) :isolation/shared-kernel (flaky inner faults))]
     (assoc w :inner inner :faults faults :fake fake :calls calls :hooks hooks))))
