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
  "API surface class 1: internal EDN bounded effect authority, with current scope and liveness checks."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.attenuation :as attenuation]
   [ai.miniforge.execution-grant.breach :as breach]
   [ai.miniforge.execution-grant.boundary.issuance :as issuance-boundary]
   [ai.miniforge.execution-grant.boundary.opsv-authorization :as opsv-authorization]
   [ai.miniforge.execution-grant.boundary.results :as result]
   [ai.miniforge.execution-grant.constraints :as constraints]
   [ai.miniforge.execution-grant.core :as core]
   [ai.miniforge.execution-grant.eligibility :as eligibility]
   [ai.miniforge.execution-grant.issuance.policy :as issuance-policy]
   [ai.miniforge.execution-grant.lineage :as lineage]
   [ai.miniforge.execution-grant.messages :as msg]
   [ai.miniforge.execution-grant.opsv-scope-schema :as opsv-schema]
   [ai.miniforge.execution-grant.revocation :as revocation]
   [ai.miniforge.execution-grant.schema :as schema]
   [ai.miniforge.execution-grant.store :as store]
   [ai.miniforge.execution-grant.store-schema :as store-schema]
   [malli.core :as m])
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

(def ^{:stratum 0} OpsvExperimentScope
  "Complete temporary staging authority bindings; not preflight, ownership, or provider proof."
  opsv-schema/Scope)

(def ^{:stratum 0} experiment-authority-required?
  "Whether an effect class/proposal requires explicit experiment authority; classification only, not permission."
  opsv-schema/experiment-effect?)

(defn ^{:stratum 0} valid?
  "Validate a value against the closed grant schema."
  [grant]
  (m/validate schema/ExecutionGrant grant))

(def ^{:stratum 0 :private true} invalid result/invalid)

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
  opsv-authorization/authorize)

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

(def ^{:stratum 0} issuance-policies
  "Runtime-owned issuance catalog keyed by effect class or explicit profile."
  issuance-policy/policies)

(def ^{:stratum 0} issue-for-effect
  "Issue narrow authority from a runtime-attested canonical request.
   Runtime composition supplies active workflow, exact effect/target and preflight.
   Principal, ceilings, expiry and delegation are policy-owned. Staging experiments
   require their explicit profile; this does not authenticate staging or ownership."
  issuance-boundary/issue-for-effect)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} register!
  "API surface class 1: register a runtime-issued grant in trusted local storage.
   Create-only; a duplicate UUID returns conflict, never replaces authority.
   Both revocation fields must be nil. Results normalize timestamps to Instant.
   Records live under dir/grants, separate from breach-history files.
   Use a canonical root path; linked directory components are refused.
   Scope must round-trip through EDN. This does not issue or authenticate grants."
  [dir grant-record]
  (let [args [dir grant-record]]
    (if (valid-input? store-schema/RegisterArguments args)
      (store/register! dir grant-record)
      (invalid :store/invalid store-schema/RegisterArguments args))))

(defn ^{:stratum 1} current
  "API surface class 1: read current durable authority, nil when absent, or anomaly.
   Includes revocation state; expiry is still evaluated by authorize at use time."
  [dir id]
  (let [args [dir id]]
    (if (valid-input? store-schema/LookupArguments args)
      (store/current dir id)
      (invalid :store/invalid store-schema/LookupArguments args))))

(defn ^{:stratum 1} revoke-stored!
  "API surface class 1: persist revocation without changing issued scope.
   Sequential retries return the first revocation. Concurrent writers may return
   conflict; re-read current authority. Never restores a revoked grant."
  [dir id reason now]
  (let [args [dir id reason now]]
    (if (valid-input? store-schema/RevokeArguments args)
      (store/revoke! dir id reason now)
      (invalid :store/invalid store-schema/RevokeArguments args))))

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

(comment ::runtime-grants-are-not-authenticated-agent-output)
