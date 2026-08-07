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
(ns ai.miniforge.execution-grant.core
  "Issuance, delegation, and revocation for ExecutionGrants.

   The factories here are the ONLY way to mint a grant, and none of
   them accepts a caller-supplied `:grant/id`, `:grant/issued-at`, or
   revocation stamp. That is the same §13.4 rule DecisionEnvelope
   enforces, applied to authority instead of verdicts: agent output can
   REQUEST authority; only the runtime ISSUES it. A grant arriving as
   agent text is data shaped like a grant, never a grant."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.attenuation :as attenuation]
   [ai.miniforge.execution-grant.lineage :as lineage]
   [ai.miniforge.execution-grant.messages :as msg]
   [ai.miniforge.execution-grant.schema :as schema]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

;; Validation
(defn ^{:stratum 0} valid?
  "True when `g` satisfies the closed ExecutionGrant schema."
  [g]
  (m/validate schema/ExecutionGrant g))

(defn- ^{:stratum 0} invalid
  "An `:invalid-input` anomaly carrying a Malli explanation."
  [message-key g]
  (anomaly/sub-anomaly :invalid-input
                       :anomalies.execution-grant/invalid
                       (msg/t message-key)
                       {:explain (me/humanize (m/explain schema/ExecutionGrant g))}))

(defn- ^{:stratum 0} refused
  "An `:unauthorized` anomaly: the inputs were well-formed but the
   runtime refuses to issue. Distinct from `invalid` on purpose — 'you
   asked for something malformed' and 'you asked for authority you do
   not have' are different answers and route differently."
  [message-key data]
  (anomaly/sub-anomaly :unauthorized
                       :anomalies.execution-grant/refused
                       (msg/t message-key)
                       data))

;; Assembly
(defn- ^{:stratum 0} assemble
  "Build a grant map from caller-supplied bounds plus runtime-owned
   identity. `:grant/id` and `:grant/issued-at` are minted here and
   never accepted from a caller; a fresh grant is never born revoked."
  [{:keys [principal effect-class parent-id delegable? expires-at] :as opts}
   ^Instant now]
  {:grant/id (random-uuid)
   :grant/principal principal
   :grant/effect-class effect-class
   :grant/scope (get opts :scope {})
   :grant/constraints (get opts :constraints {})
   :grant/parent-id parent-id
   :grant/delegable? (boolean delegable?)
   :grant/issued-at now
   :grant/expires-at expires-at
   :grant/revoked-at nil
   :grant/revocation-reason nil})

;------------------------------------------------------------------------------ Layer 1

;; Factories
(defn ^{:stratum 1} issue
  "Issue a ROOT grant (no parent). `opts` carries `:principal`,
   `:effect-class`, `:scope`, `:constraints`, `:delegable?`, and
   `:expires-at`. Returns the grant, or an `:invalid-input` anomaly."
  ([opts] (issue opts (Instant/now)))
  ([opts now]
   (let [g (assemble (assoc opts :parent-id nil) now)]
     (if (valid? g)
       g
       (invalid :grant/input-invalid g)))))

(defn- ^{:stratum 1} parent-refusal
  "Return why a parent cannot delegate, or nil when it may."
  [parent ^Instant now]
  (cond
    (not (valid? parent))
    (invalid :grant/parent-invalid parent)

    (not (:grant/delegable? parent))
    (refused :grant/parent-not-delegable
             {:grant/id (:grant/id parent)})

    (not (lineage/live? parent now))
    (refused :grant/parent-inactive
             {:grant/id (:grant/id parent)
              :grant/revocation-reason (:grant/revocation-reason parent)})

    :else nil))

(defn- ^{:stratum 1} delegated-child
  "Assemble and assess one requested child grant."
  [parent opts ^Instant now]
  (let [parent-id (:grant/id parent)
        effect-class (:grant/effect-class parent)
        child-opts (assoc opts :parent-id parent-id :effect-class effect-class)
        child (assemble child-opts now)
        valid-child? (valid? child)
        widened (when valid-child? (attenuation/violations parent child))]
    (cond
      (not valid-child?)
      (invalid :grant/delegated-invalid child)

      (seq widened)
      (refused :grant/delegation-widened
               {:grant/id parent-id
                :attenuation/violations widened})

      :else child)))

(defn ^{:stratum 1} revoke
  "Revoke `grant` for `reason`, preserving the record.

   Revocation is idempotent in effect but not in fact: re-revoking an
   already-revoked grant returns it unchanged, so the ORIGINAL cause
   and time survive. Overwriting them would lose the first breach,
   which is the one step 2e needs to remember."
  ([grant reason] (revoke grant reason (Instant/now)))
  ([grant reason ^Instant now]
   (cond
     (not (valid? grant))
     (invalid :grant/revoke-invalid grant)

     (lineage/revoked? grant) grant

     :else
     (let [g (assoc grant :grant/revoked-at now :grant/revocation-reason reason)]
       (if (valid? g)
         g
         (invalid :grant/revocation-invalid g))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} delegate
  "Cut a child grant from `parent`.

   Refuses when the parent cannot delegate or the requested child
   widens any authority axis. The assembled child, rather than caller
   intent, is checked so an omitted bound cannot become unbounded."
  ([parent opts] (delegate parent opts (Instant/now)))
  ([parent opts ^Instant now]
   (or (parent-refusal parent now)
       (delegated-child parent opts now))))
