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
(ns ai.miniforge.effect-transaction.schema
  "The EffectTransaction shape (Ariadne step 2c, §13.5): an irreversible
   effect as a transaction with a durable record written BEFORE the
   effect and a reconciliation that discovers what actually happened.

   The state that earns this component its keep is `:unknown-outcome`.
   Today's effects report outcomes they cannot substantiate —
   `merge-pr!` passes `--auto` unconditionally and returns
   `{:merged? true}`, then publishes a SHA read from the LOCAL worktree.
   Claiming failure for a merge that actually landed is exactly as wrong
   as claiming success; `:unknown-outcome` is the honest third answer,
   and reconciliation is what resolves it."
  (:require
   [ai.miniforge.execution-grant.interface :as grant]))

;------------------------------------------------------------------------------ Layer 0

;; Vocabulary
(def ^{:stratum 0} states
  "Transaction lifecycle.

   `:proposed`        record durable, effect NOT yet attempted
   `:committing`      grant re-checked, effect in flight — a record
                      found here after a restart is unknown, not failed
   `:succeeded`       the effect reported definite success
   `:failed`          the effect reported definite failure, or the
                      commit was refused before the effect fired
   `:unknown-outcome` the effect may or may not have happened
   `:reconciled`      the external system was asked and answered"
  [:proposed :committing :succeeded :failed :unknown-outcome :reconciled])

(def ^{:stratum 0} terminal-states
  "States that need no reconciliation: the outcome is already known."
  #{:succeeded :failed :reconciled})

(def ^{:stratum 0} reconcilable-states
  "States whose true outcome is NOT known locally and must be settled by
   asking the external system. `:committing` is here because a process
   that died mid-effect leaves the record exactly there."
  #{:committing :unknown-outcome})

;------------------------------------------------------------------------------ Layer 1

;; The record
(def ^{:stratum 1} EffectTransaction
  "CLOSED map — an effect nobody can describe is an effect nobody can
   reconcile.

   `:effect/observed` is what the WORLD said, never what the local code
   guessed. `:effect/matched?` records whether the observation agreed
   with the proposal; a mismatch is kept, not smoothed over — the point
   of reconciling is to find out, and finding out includes finding out
   you were wrong."
  [:map {:closed true}
   [:effect/id :uuid]
   [:effect/class (into [:enum] grant/effect-classes)]
   [:effect/grant-id [:maybe :uuid]]
   [:effect/envelope-id {:optional true} [:maybe :uuid]]
   [:effect/proposal [:map-of :keyword any?]]
   [:effect/state (into [:enum] states)]
   [:effect/observed {:optional true} any?]
   [:effect/matched? {:optional true} [:maybe :boolean]]
   [:effect/failure {:optional true} [:maybe :string]]
   [:effect/at inst?]
   [:effect/updated-at inst?]])
