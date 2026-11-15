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
(ns ai.miniforge.agent-environment.ledger.file
  "A durable ledger store: one record file per key under a directory. A
   write holds the key's lock, checks the stored revision, and returns only
   once the new record is on stable storage, so a controller started later,
   in this process or another, reads every acknowledged write."
  (:require
   [ai.miniforge.agent-environment.interface.ledger :as ledger]
   [ai.miniforge.agent-environment.ledger.disk :as disk]
   [ai.miniforge.agent-environment.refusal :as refusal]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} listed
  [dir]
  (anomaly/let-ok [files (disk/record-files dir)]
    (let [records (mapv disk/read-record files)]
      (or (first (filter anomaly/anomaly? records)) records))))

(defn- ^{:stratum 0} written
  "Publish `entry` as `k`'s next record while `revision` is still the
   stored one, else the conflict a lost race returns. Runs under the
   key's lock."
  [dir k revision entry]
  (let [target (disk/record-file dir k)
        stored (disk/read-record target)
        next-revision ((fnil inc 0) revision)
        record {:ledger/key k :ledger/entry entry :ledger/revision next-revision}]
    (cond
      (anomaly/anomaly? stored) stored
      (not= revision (:ledger/revision stored)) (refusal/reason-anomaly :anomalies.agent-environment/ledger-conflict
                                                                        {:ledger/key k})
      :else (anomaly/let-ok [_ (disk/publish! dir target record)] {:ledger/revision next-revision}))))

;------------------------------------------------------------------------------ Layer 1

(defrecord ^{:stratum 1} FileLedger [dir]
  ledger/LedgerStore
  (read-entry [_ k]
    (anomaly/let-ok [stored (disk/read-record (disk/record-file dir k))]
      (select-keys stored [:ledger/entry :ledger/revision])))
  (write-entry! [_ k revision entry]
    (disk/with-lock dir k (partial written dir k revision entry)))
  (entries [_] (listed dir)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create
  [dir]
  (->FileLedger (io/file dir)))
