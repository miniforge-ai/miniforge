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
(ns ai.miniforge.effect-transaction.interface
  "Public API for the effect-transaction component (Ariadne step 2c):
   an irreversible effect as a durable record.

   This half is the RECORD and its store. The propose/commit/reconcile
   coordinator lands next, and 2d moves merge and deploy onto it."
  (:require
   [ai.miniforge.effect-transaction.schema :as schema]
   [ai.miniforge.effect-transaction.store :as store]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} states
  "Transaction lifecycle states."
  schema/states)

(def ^{:stratum 0} terminal-states
  "States needing no reconciliation."
  schema/terminal-states)

(def ^{:stratum 0} reconcilable-states
  "States whose true outcome must be settled by asking the world."
  schema/reconcilable-states)

(def ^{:stratum 0} EffectTransaction
  "Closed Malli schema for a transaction record."
  schema/EffectTransaction)

(def ^{:stratum 0} write!
  "Persist a record atomically. Throws if the write cannot complete —
   a caller MUST treat that as 'do not attempt the effect'."
  store/write!)

(def ^{:stratum 0} read-record
  "Read one persisted record by id, or nil."
  store/read-record)

(def ^{:stratum 0} list-records
  "Every persisted record under a directory."
  store/list-records)

(defn ^{:stratum 0} valid?
  "True when `t` satisfies the closed EffectTransaction schema."
  [t]
  (m/validate schema/EffectTransaction t))
