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
(ns ai.miniforge.execution-grant.interface
  "Public API for the execution-grant component (Ariadne step 2a):
   bounded authority to perform an irreversible effect.

   Decision gating consumes authorization results, and
   effect-transaction rechecks grants at commit. Production call sites
   still await the runtime issuer before they can remove their explicit
   unenforced-authority marker. This component owns the object and its
   rules: issuance, attenuated delegation, revocation as a state, scope
   and ceiling authorization, and liveness over lineage."
  (:require
   [ai.miniforge.execution-grant.attenuation :as attenuation]
   [ai.miniforge.execution-grant.breach :as breach]
   [ai.miniforge.execution-grant.eligibility :as eligibility]
   [ai.miniforge.execution-grant.constraints :as constraints]
   [ai.miniforge.execution-grant.core :as core]
   [ai.miniforge.execution-grant.lineage :as lineage]
   [ai.miniforge.execution-grant.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} effect-classes
  "The closed set of irreversible effects a grant may authorize."
  schema/effect-classes)

(def ^{:stratum 0} constraint-axes
  "Ceiling axes a grant may carry; every axis is an upper bound."
  schema/constraint-axes)

(def ^{:stratum 0} revocation-reasons
  "Why a grant was revoked, including the `:breach/*` for-cause set."
  schema/revocation-reasons)

(def ^{:stratum 0} ExecutionGrant
  "Closed Malli schema for a grant."
  schema/ExecutionGrant)

(def ^{:stratum 0} valid?
  "Validate a value against the closed grant schema."
  core/valid?)

(def ^{:stratum 0} issue
  "Issue a root grant; id and issued-at are runtime-owned."
  core/issue)

(def ^{:stratum 0} delegate
  "Cut an attenuated child grant from a parent, or refuse with a
   `:unauthorized` anomaly naming the widened axis."
  core/delegate)

(def ^{:stratum 0} revoke
  "Revoke a grant for a reason, preserving the record."
  core/revoke)

(def ^{:stratum 0} revoked?
  "Local revocation stamp only; says nothing about ancestors."
  lineage/revoked?)

(def ^{:stratum 0} expired?
  "True when the grant has passed its expiry as of the given instant."
  lineage/expired?)

(def ^{:stratum 0} active?
  "True when the grant and every ancestor are live. The 2-arity form is
   root-only; pass a `lookup` to verify a delegated grant."
  lineage/active?)

(def ^{:stratum 0} ancestry
  "The lineage chain from a grant upward; a nil element marks an
   unresolvable ancestor."
  lineage/ancestry)

(def ^{:stratum 0} attenuates?
  "True when a child grant is a legal narrowing of its parent."
  attenuation/attenuates?)

(def ^{:stratum 0} attenuation-violations
  "Every axis on which a child fails to attenuate its parent."
  attenuation/violations)

(def ^{:stratum 0} authorize
  "The one check site: is this effect within its grant, as of now?
   Called at decide() and AGAIN at commit — a grant live at the first
   call and revoked before the second fails the second."
  constraints/authorize)

(def ^{:stratum 0} authorized?
  "True when an `authorize` result is `:authorized`."
  constraints/authorized?)

(def ^{:stratum 0} breaches
  "Ceilings a usage reading exceeds on a grant."
  constraints/breaches)

(def ^{:stratum 0} budget->constraints
  "Translate a legacy budget map into `:grant/constraints`."
  constraints/budget->constraints)

(def ^{:stratum 0} Breach
  "Closed Malli schema for a recorded breach."
  schema/Breach)

(def ^{:stratum 0} detections
  "How a breach came to light: :prevented (a gate refused first) or
   :detected (reconciliation found it after the effect)."
  schema/detections)

(def ^{:stratum 0} record-breach!
  "Append one breach to the history. One file per breach, never
   rewritten — append-only by construction."
  breach/record!)

(def ^{:stratum 0} breach-history
  "Every recorded breach, optionally narrowed to one principal."
  breach/history)

(def ^{:stratum 0} permitted-ceiling
  "The highest ceiling a principal may now be granted on an axis, or nil
   for unbounded."
  eligibility/permitted-ceiling)

(def ^{:stratum 0} eligible?
  "May this principal be granted this effect class at these ceilings?"
  eligibility/eligible?)

(def ^{:stratum 0} revoke-for-cause!
  "Revoke a grant AND record the breach that caused it."
  eligibility/revoke-for-cause!)
