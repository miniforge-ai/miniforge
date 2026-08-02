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
(ns ai.miniforge.execution-grant.schema
  "The ExecutionGrant shape (Ariadne step 2a, §13.5): bounded authority
   to perform an irreversible effect, so authority stops being ambient.

   The schema is CLOSED, for the same reason DecisionEnvelope's is: a
   grant is a contract, not an open convention. An unknown key on a
   grant is a grant nobody can reason about.

   This subsumes N10 §6's `:capability/*` model — same object, Ariadne
   vocabulary. N10's spec text is rewritten as a profile of this
   interface in adoption step 4; it is not a second mechanism.")

;------------------------------------------------------------------------------ Layer 0

;; Vocabulary
(def ^{:stratum 0} effect-classes
  "The closed set of irreversible effects a grant may authorize. Closed
   on purpose: an effect nobody enumerated is an effect nobody bounded,
   so a new effect class is a deliberate contract change, not a runtime
   surprise."
  [:effect/merge
   :effect/deploy
   :effect/spend
   :effect/push
   :effect/pr-create])

(def ^{:stratum 0} constraint-axes
  "Ceiling axes a grant may carry. Every axis is an upper bound: a
   smaller value is strictly more restrictive, which is what makes
   attenuation checkable by comparison."
  [:constraint/max-cost-usd
   :constraint/max-tokens
   :constraint/max-count])

(def ^{:stratum 0} revocation-reasons
  "Why a grant was revoked. `:revocation/superseded` and
   `:revocation/operator` are administrative; the `:breach/*` reasons
   are revocation FOR CAUSE and are what step 2e records against a
   principal's history."
  [:revocation/operator
   :revocation/superseded
   :revocation/parent-revoked
   :breach/cost-exceeded
   :breach/token-exceeded
   :breach/count-exceeded
   :breach/scope-violation])

;; Sub-schemas
(def ^{:stratum 0} Constraints
  "Ceilings on a grant. CLOSED, and every axis optional: an absent axis
   means unbounded on that axis, which is exactly why `delegate` refuses
   to let a child omit an axis its parent bounds.

   Expiry is deliberately NOT an axis here — `:grant/expires-at` is the
   single expiry authority. Two places to read a TTL is how a governance
   component ends up honoring the laxer one."
  [:map {:closed true}
   [:constraint/max-cost-usd {:optional true} number?]
   [:constraint/max-tokens {:optional true} nat-int?]
   [:constraint/max-count {:optional true} nat-int?]])

(def ^{:stratum 0} Scope
  "What the grant is bounded TO — repo, PR number, namespace, workflow
   run. Open by design: scope keys are domain-specific and a new one
   NARROWS, it never widens. Containment (not shape) is what
   `delegate` checks."
  [:map-of :keyword any?])

(def ^{:stratum 0} detections
  "How a breach came to light (Ariadne 2e).

   `:prevented` — a gate refused before the effect happened.
   `:detected`  — reconciliation found it afterwards; the effect already
                  occurred. Recording this as `:prevented` would claim a
                  gate that does not exist."
  [:prevented :detected])

;------------------------------------------------------------------------------ Layer 1

;; The grant
(def ^{:stratum 1} ExecutionGrant
  "CLOSED map — see ns docstring.

   `:grant/parent-id` is the lineage link: a delegated grant names the
   grant it was cut from, and `active?` WALKS that chain rather than
   trusting a local flag. That walk is what makes revoking a parent
   contain everything cut from it — the property that makes a delegated
   inner orchestrator boundable at all.

   `:grant/revoked-at` + `:grant/revocation-reason` record revocation as
   a STATE, not a deletion. A revoked grant stays readable: the audit
   question is not only what authority exists but what authority
   existed and why it ended."
  [:map {:closed true}
   [:grant/id :uuid]
   [:grant/principal :string]
   [:grant/effect-class (into [:enum] effect-classes)]
   [:grant/scope Scope]
   [:grant/constraints Constraints]
   [:grant/parent-id [:maybe :uuid]]
   [:grant/delegable? :boolean]
   [:grant/issued-at inst?]
   [:grant/expires-at inst?]
   [:grant/revoked-at [:maybe inst?]]
   [:grant/revocation-reason [:maybe (into [:enum] revocation-reasons)]]])

(def ^{:stratum 1} Breach
  "CLOSED record of one ceiling a principal exceeded (Ariadne 2e)."
  [:map {:closed true}
   [:breach/id :uuid]
   [:breach/principal :string]
   [:breach/grant-id :uuid]
   [:breach/effect-class (into [:enum] effect-classes)]
   [:breach/axis (into [:enum] constraint-axes)]
   [:breach/limit number?]
   [:breach/observed number?]
   [:breach/detection (into [:enum] detections)]
   [:breach/at inst?]])
