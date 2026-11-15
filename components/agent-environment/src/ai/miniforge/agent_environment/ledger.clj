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
(ns ai.miniforge.agent-environment.ledger
  "The controller's access to its ledger store: read one entry, change one
   by compare-and-set, list them all. A lost race is retried against the
   entry that won it. A store failure without a ledger reason is reported
   as the ledger being unavailable."
  (:require
   [ai.miniforge.agent-environment.interface.ledger :as store]
   [ai.miniforge.agent-environment.refusal :as refusal]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} write-attempts
  "Compare-and-set attempts before a key still changing under every write
   is reported as contended. A lost race normally settles on the next
   attempt, because the re-read entry no longer needs the write."
  8)

(defn- ^{:stratum 0} change-once
  "Read `k`, apply `f`, and write the result when it differs. Returns
   `{:ledger/before e :ledger/after e'}` or the store's anomaly."
  [ledger k f]
  (anomaly/let-ok [stored (store/read-entry ledger k)
                   before (:ledger/entry stored)
                   after (f before)
                   _ (when (not= before after) (store/write-entry! ledger k (:ledger/revision stored) after))]
    {:ledger/before before :ledger/after after}))

(def ^{:stratum 0} ledger-reasons
  "The only reasons a ledger failure carries; a store anomaly with any other
   is reported as the ledger being unavailable."
  #{:anomalies.agent-environment/ledger-conflict :anomalies.agent-environment/ledger-unavailable})

(defn- ^{:stratum 0} lost-race?
  [result]
  (= :anomalies.agent-environment/ledger-conflict (anomaly/subtype result)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} store-failure
  "A store anomaly carrying a ledger reason, unchanged; any other becomes
   the ledger being unavailable, with the store's anomaly kept as data."
  [result k]
  (if (contains? ledger-reasons (anomaly/subtype result))
    result
    (refusal/reason-anomaly :anomalies.agent-environment/ledger-unavailable
                            {:ledger/key k :ledger/store-anomaly result})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} entry
  "`k`'s entry, nil when it has none, or the ledger's anomaly."
  [ledger k]
  (let [stored (store/read-entry ledger k)]
    (if (anomaly/anomaly? stored) (store-failure stored k) (:ledger/entry stored))))

(defn ^{:stratum 2} all-entries
  "Every entry in the ledger, or the ledger's anomaly."
  [ledger]
  (let [listed (store/entries ledger)]
    (if (anomaly/anomaly? listed) (store-failure listed nil) (mapv :ledger/entry listed))))

(defn ^{:stratum 2} change!
  "Apply `f` to `k`'s entry by compare-and-set, retrying a lost race against
   the entry that won it; `f` returns its argument unchanged to write
   nothing. Returns `{:ledger/before e :ledger/after e'}` or the ledger's
   anomaly."
  [ledger k f]
  (loop [attempt 1]
    (let [result (change-once ledger k f)]
      (cond
        (not (anomaly/anomaly? result)) result
        (and (lost-race? result) (< attempt write-attempts)) (recur (inc attempt))
        :else (store-failure result k)))))
