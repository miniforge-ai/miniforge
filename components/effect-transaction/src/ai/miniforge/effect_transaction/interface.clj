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

   Record, durable store, and the propose/commit/reconcile coordinator.
   2d moves merge and deploy onto this path."
  (:require
   [ai.miniforge.effect-transaction.core :as core]
   [ai.miniforge.effect-transaction.schema :as schema]
   [ai.miniforge.effect-transaction.store :as store]))

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

(def ^{:stratum 0} read-record
  "Read one persisted record by UUID, nil, or an input/storage anomaly."
  store/read-record)

(def ^{:stratum 0} list-records
  "Every persisted record under a directory, or a storage anomaly."
  store/list-records)

(def ^{:stratum 0} valid?
  "True when `t` satisfies the closed EffectTransaction schema."
  core/valid?)

(def ^{:stratum 0} propose!
  "Create a durable proposal without replacing an existing effect ID."
  core/propose!)

(def ^{:stratum 0} commit!
  "Reload the durable proposal, authorize its scope and one-operation count,
   atomically mark :committing, run the effect, and record what it reported.
   Caller usage is ignored. An Exception is :unknown-outcome, never :failed;
   a JVM Error propagates after the durable claim."
  core/commit!)

(def ^{:stratum 0} reconcile!
  "Reload a durable unsettled record, ask the external system what happened,
   and record the answer, mismatch included."
  core/reconcile!)
