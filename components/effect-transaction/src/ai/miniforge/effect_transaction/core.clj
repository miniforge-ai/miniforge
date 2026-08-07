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
(ns ai.miniforge.effect-transaction.core
  "propose -> commit -> reconcile facade for irreversible effects.

   A proposed effect is durable before execution. An effect call that
   throws has an unknown outcome, never a fabricated failure, and must
   be reconciled by asking the external system what actually happened."
  (:require
   [ai.miniforge.effect-transaction.commit :as commit]
   [ai.miniforge.effect-transaction.reconcile :as reconcile]
   [ai.miniforge.effect-transaction.record :as record]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} valid?
  "True when `t` satisfies the closed EffectTransaction schema."
  record/valid?)

(def ^{:stratum 0} propose!
  "Record the intent durably before an irreversible effect happens."
  record/propose!)

(def ^{:stratum 0} commit!
  "Authorize and execute a durable proposal, recording an honest outcome."
  commit/commit!)

(def ^{:stratum 0} reconcile!
  "Ask the external system to settle an unknown effect outcome."
  reconcile/reconcile!)
