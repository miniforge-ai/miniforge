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
  "API surface class 1: internal EDN durable effect transactions and commit-time authority."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.boundary.provider-dispatch :as dispatch]
   [ai.miniforge.effect-transaction.core :as core]
   [ai.miniforge.effect-transaction.current-commit :as current]
   [ai.miniforge.effect-transaction.current-schema :as current-schema]
   [ai.miniforge.effect-transaction.messages :as msg]
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

(def ^{:stratum 0} provider-dispatch-diagnostics
  "Construct closed provider diagnostics from localized unconfirmed and invalid-input messages,
   or return an invalid-input anomaly. Providers need not duplicate the message-map shape."
  dispatch/diagnostics)

(def ^{:stratum 0} call-with-provider-dispatch!
  "Run a trusted unary synchronous dispatch callback around at most one provider attempt.
   Accept dispatch and operation, optionally closed provider-owned localized diagnostics.
   The two-argument form uses this component's system catalog; providers may preserve their own.
   Operation returns its provider report or anomaly; a confirmed report cannot be replaced.
   Reject repeated, cross-thread and escaped thunk attempts; close admission on return.
   Exceptions without confirmation yield failed-before-attempt or unknown-after-attempt data.
   Preserve interruption. This does not establish N4/N8 admission, grant validity or durability."
  dispatch/call)

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
   a JVM Error propagates after the durable claim. Experiment effects require
   commit-current!; legacy snapshots and unenforced markers are refused."
  core/commit!)

(def ^{:stratum 0} reconcile!
  "Reload a durable unsettled record, ask the external system what happened,
   and record the answer, mismatch included."
  core/reconcile!)

(defn ^{:stratum 0} commit-current!
  "API surface class 1: trusted-process runtime commit, returning EDN or anomaly.
   Load by UUID, resolve its named grant, then read the clock and authorize.
   lookup-grant (UUID -> current grant) and clock (() -> Instant) are runtime
   ports, never model-supplied functions. They must return anomalies on failure.
   effect-fn receives the exact :committing record after its durable claim.
   Unavailable authority leaves the proposal intact. Denied authority records
   failure. No unenforced-authority path exists. Delegated grants fail closed.
   This recheck does not lock the grant store against subsequent revocation."
  [dir id lookup-grant clock effect-fn]
  (let [arguments [dir id lookup-grant clock effect-fn]]
    (if (m/validate current-schema/CommitArguments arguments)
      (current/commit! dir id lookup-grant clock effect-fn)
      (anomaly/anomaly :invalid-input (msg/t :commit/invalid-input) {}))))
