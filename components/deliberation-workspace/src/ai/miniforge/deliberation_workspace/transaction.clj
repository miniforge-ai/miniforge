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
(ns ai.miniforge.deliberation-workspace.transaction
  "The closed transaction operation vocabulary of N14 §3.2 and the
   concurrency classes of §3.3. Pure data — validation and application
   live in sibling namespaces.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} operations
  "Closed operation vocabulary (N14 §3.2). A transaction carrying anything
   outside this set is rejected at schema conformance."
  #{:assert-claim :refine-claim :challenge :attach-evidence
    :add-question :answer-question :retire-question
    :propose-hypothesis :split-hypothesis :merge-hypotheses
    :propose-experiment :record-experiment-result
    :propose-plan :revise-plan
    :propose-decision :accept-decision :reject-decision
    :register-artifact :invalidate-artifact
    :add-goal :add-constraint :declare-blocked :close-goal})

(def ^{:stratum 0} operation-class
  "Concurrency class per operation (N14 §3.3).

   :additive   commutes; commits even on a stale basis provided the objects
               it touches still exist and are non-terminal.
   :mergeable  commits only when the objects it touches are unchanged since
               the basis version.
   :exclusive  commits only against the current version of touched objects.

   `:split-hypothesis` is classified :exclusive here: it rewrites an existing
   hypothesis into parts, so two concurrent splits of one parent would
   silently diverge. N14 §3.3's table omits it — tracked as a spec gap."
  {:assert-claim :additive
   :add-question :additive
   :attach-evidence :additive
   :propose-hypothesis :additive
   :propose-experiment :additive
   :propose-plan :additive
   :propose-decision :additive
   :register-artifact :additive
   :challenge :additive
   :declare-blocked :additive
   :refine-claim :mergeable
   :revise-plan :mergeable
   :answer-question :mergeable
   :accept-decision :exclusive
   :reject-decision :exclusive
   :record-experiment-result :exclusive
   :merge-hypotheses :exclusive
   :split-hypothesis :exclusive
   :invalidate-artifact :exclusive
   :close-goal :exclusive
   :add-goal :exclusive
   :add-constraint :exclusive
   :retire-question :exclusive})

(def ^{:stratum 0} role-permissions
  "Operations each role may propose (N14 §5.3). Roles absent from a given
   operation's permitted set are rejected at the permission stage.

   The user acts through OCI, not through a role, and is exempt (§10.2)."
  {:record-experiment-result #{:verifier}
   :propose-decision #{:synthesizer}
   :accept-decision #{:synthesizer}
   :reject-decision #{:synthesizer}
   :close-goal #{:synthesizer}
   :add-goal #{:interpreter}
   :add-constraint #{:interpreter}
   :retire-question #{:meta-watchdog}})

(def ^{:stratum 0} ^:private universal-operations
  "Operations every role may propose (N14 §5.3, final clause)."
  #{:assert-claim :add-question :declare-blocked})

(defn ^{:stratum 0} touched-ids
  "The object ids `operation` reads or writes. Every operation must declare
   these so the validator can check basis staleness without interpreting
   operation semantics."
  [operation]
  (set (get operation :targets #{})))

(defn ^{:stratum 0} new-transaction
  "Build a transaction proposal. `basis` is the workspace version the
   activation's projection was rendered from (N14 §3.1)."
  [{:keys [role activation basis operations]}]
  {:tx/role role
   :tx/activation activation
   :tx/basis basis
   :tx/operations (vec operations)})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} known-operation?
  "True when `op` is in the closed vocabulary."
  [op]
  (contains? operations op))

(defn ^{:stratum 1} class-of
  "Concurrency class of `op`, or nil when `op` is not in the vocabulary."
  [op]
  (get operation-class op))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} permitted?
  "True when `role` may propose `op` (N14 §5.3). Operations with no explicit
   restriction are open to every role.

   An operation outside the vocabulary is never permitted: schema conformance
   rejects it first, but the permission gate must not be the layer that lets
   an unknown operation through.

   `:user` is the OCI principal, not a role. Per §10.2 its operations are
   subject to N8 audit rather than the §5.3 role matrix, so it passes any
   operation in the vocabulary."
  [role op]
  (cond
    (not (known-operation? op)) false
    (= :user role) true
    (contains? universal-operations op) true
    (contains? role-permissions op) (contains? (get role-permissions op) role)
    :else true))
