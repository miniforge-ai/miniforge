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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.attenuation :as attenuation]
   [ai.miniforge.execution-grant.breach :as breach]
   [ai.miniforge.execution-grant.constraints :as constraints]
   [ai.miniforge.execution-grant.core :as core]
   [ai.miniforge.execution-grant.eligibility :as eligibility]
   [ai.miniforge.execution-grant.lineage :as lineage]
   [ai.miniforge.execution-grant.messages :as msg]
   [ai.miniforge.execution-grant.revocation :as revocation]
   [ai.miniforge.execution-grant.schema :as schema]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   [java.time Instant]))

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

(defn ^{:stratum 0} valid?
  "Validate a value against the closed grant schema."
  [grant]
  (m/validate schema/ExecutionGrant grant))

(defn- ^{:stratum 0} invalid
  "Return an invalid-input anomaly with the boundary explanation."
  [message-key input-schema value]
  (anomaly/sub-anomaly
   :invalid-input
   :anomalies.execution-grant/invalid
   (msg/t message-key)
   {:explain (me/humanize (m/explain input-schema value))}))

(defn- ^{:stratum 0} valid-input?
  "True when `value` satisfies its component-boundary schema."
  [input-schema value]
  (m/validate input-schema value))

(defn- ^{:stratum 0} invalid-breach
  "Return malformed breach data as an invalid-input anomaly."
  [breach]
  (anomaly/sub-anomaly :invalid-input
                       :anomalies.execution-grant/invalid-breach
                       (msg/t :breach/invalid)
                       {:breach breach}))

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

(def ^{:stratum 0} breach-history
  "Every recorded breach, optionally narrowed to one principal. Returns
   an anomaly rather than treating unreadable history as empty."
  breach/history)

(def ^{:stratum 0} permitted-ceiling
  "The highest ceiling a principal may now be granted on an axis, or nil
   for unbounded."
  eligibility/permitted-ceiling)

(def ^{:stratum 0} eligible?
  "May this principal be granted this effect class at these ceilings?"
  eligibility/eligible?)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record-breach!
  "Append one breach to the history, or return an anomaly. One file per
   breach, never rewritten — append-only by construction."
  [dir breach-record]
  (if (valid-input? schema/Breach breach-record)
    (breach/record! dir breach-record)
    (invalid-breach breach-record)))

(defn ^{:stratum 1} revoke-for-cause!
  "Revoke a grant AND record the breach that caused it, or return an
   input or recording anomaly without revoking."
  [dir grant observation now]
  (let [arguments [grant observation now]]
    (if (valid-input? schema/RevokeForCauseArguments arguments)
      (revocation/revoke-for-cause! dir grant observation now)
      (invalid :breach/revocation-invalid
               schema/RevokeForCauseArguments
               arguments))))

(defn ^{:stratum 1} issue
  "Issue a root grant; id and issued-at are runtime-owned. Invalid
   boundary data is returned as an `:invalid-input` anomaly."
  ([opts] (issue opts (Instant/now)))
  ([opts now]
   (let [arguments [opts now]]
     (if (valid-input? schema/IssueArguments arguments)
       (core/issue opts now)
       (invalid :grant/input-invalid schema/IssueArguments arguments)))))

(defn ^{:stratum 1} delegate
  "Cut an attenuated child grant from a parent, or refuse with a
   `:unauthorized` anomaly naming the widened axis."
  ([parent opts] (delegate parent opts (Instant/now)))
  ([parent opts now]
   (let [arguments [opts now]]
     (cond
       (not (valid? parent))
       (invalid :grant/parent-invalid schema/ExecutionGrant parent)

       (not (valid-input? schema/DelegationArguments arguments))
       (invalid :grant/delegated-invalid schema/DelegationArguments arguments)

       :else (core/delegate parent opts now)))))

(defn ^{:stratum 1} revoke
  "Revoke a grant for a reason, preserving the record."
  ([grant reason] (revoke grant reason (Instant/now)))
  ([grant reason now]
   (let [arguments [reason now]]
     (cond
       (not (valid? grant))
       (invalid :grant/revoke-invalid schema/ExecutionGrant grant)

       (not (valid-input? schema/RevocationArguments arguments))
       (invalid :grant/revocation-invalid schema/RevocationArguments arguments)

       :else (core/revoke grant reason now)))))
