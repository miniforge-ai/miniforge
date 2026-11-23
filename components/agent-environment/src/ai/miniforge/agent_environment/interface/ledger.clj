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
(ns ai.miniforge.agent-environment.interface.ledger
  "API surface class 1: where a controller keeps its lease ledger, one entry
   per tenant/run/task key under a store-assigned revision. Controllers
   sharing a store rely on three guarantees: (1) a write succeeds only while
   the revision it names (nil: no entry) is still the key's, so two writes
   naming one revision cannot both succeed; (2) a read returns the latest
   acknowledged write, and a durable store acknowledges only what survives
   its own restart; (3) a key's revisions only grow. Operations return data:
   a lost race is a `:anomalies.agent-environment/ledger-conflict` anomaly,
   a store that cannot answer `:anomalies.agent-environment/ledger-unavailable`.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} absent
  "What `read-entry` returns for a key with no entry."
  {:ledger/entry nil :ledger/revision nil})

(defprotocol ^{:stratum 0} LedgerStore
  (read-entry [store k]
    "`{:ledger/entry entry-or-nil :ledger/revision n-or-nil}` for key `k`.")
  (write-entry! [store k revision entry]
    "Store `entry` under `k` when `revision` is still the key's revision.
     Returns `{:ledger/revision n}`, the revision the write created.")
  (entries [store]
    "Every key with an entry: `[{:ledger/key :ledger/entry :ledger/revision}]`."))
