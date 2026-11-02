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
  "The closed transaction operation vocabulary of N14 §3.2, the concurrency
   classes of §3.3, and the pure effect each operation has on one target
   object. Validation and commit live in sibling namespaces."
  (:require
   [ai.miniforge.deliberation-workspace.object :as object]))

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
    :add-goal :add-constraint :declare-blocked :close-goal :supersede})

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
   :retire-question :exclusive
   :supersede :exclusive})

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

(def ^{:stratum 0} status-effect
  "Status an operation imposes on the objects it targets (N14 §2.3).
   Operations absent from this table do not move target status.

   The engine reads the effect from the operation rather than from the
   transaction payload: an activation chooses which operation to propose,
   and this table decides what that operation does."
  {:accept-decision :accepted
   :reject-decision :rejected
   :answer-question :answered
   :retire-question :retired
   :record-experiment-result :completed
   :invalidate-artifact :superseded
   :challenge :contested
   :supersede :superseded})

(def ^{:stratum 0} revisions
  "Operations that replace their target's statement, keeping the prior one
   in the target's history (N14 §2.3)."
  #{:refine-claim :revise-plan})

(def ^{:stratum 0} reopening-operations
  "Additive operations that may still target an `:accepted` object: adoption
   is a working choice, and contrary evidence or an actionable challenge
   must remain recordable against it (N14 §2.3)."
  #{:challenge :attach-evidence})

(def ^{:stratum 0} goal-outcomes
  "Outcomes `close-goal` may impose (N14 §2.3)."
  #{:accepted :rejected})

(def ^{:stratum 0} ^:private universal-operations
  "Operations every role may propose (N14 §5.3, final clause)."
  #{:assert-claim :add-question :declare-blocked})

(def ^{:stratum 0} id-fields
  "Operation fields that name object ids (N14 §3.2).

   `:targets` is read by [[touched-ids]]; `:evidence` and `:discriminates`
   by the §3.5 backing check; `:replacement` by `supersede`. Each is
   optional, and each reader hands the field to `set` or `seq`, so
   validation holds all four to one shape."
  [:targets :evidence :discriminates :replacement])

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

(defn- ^{:stratum 0} revise
  "`target` with `statement` replacing its own, the prior kept in history."
  [target statement]
  (-> target
      (update :object/history (fnil conj [])
              (select-keys target [:object/statement :object/touched-at]))
      (assoc :object/statement statement)))

(defn ^{:stratum 0} with-status
  "`target` after `op` imposes `status`. A challenge leaves an adopted record
   `:accepted` and marks it contested instead (N14 §2.3); a status illegal
   for the target's type is skipped."
  [target op status]
  (cond
    (and (= :challenge op) (= :accepted (:object/status target)))
    (assoc target :object/contested? true)

    (and status (object/legal-status? (:object/type target) status))
    (assoc target :object/status status)

    :else target))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} known-operation?
  "True when `op` is in the closed vocabulary."
  [op]
  (contains? operations op))

(defn ^{:stratum 1} class-of
  "Concurrency class of `op`, or nil when `op` is not in the vocabulary."
  [op]
  (get operation-class op))

(defn ^{:stratum 1} with-content
  "`target` after the content an operation carries: a revision replaces the
   statement and keeps the prior one in `:object/history`, an answer is
   recorded beside its question, and a supersession names its replacement."
  [target operation]
  (let [op (:op operation)]
    (cond
      (contains? revisions op)
      (revise target (:statement operation))

      (= :answer-question op)
      (assoc target :object/answer (:statement operation))

      (= :supersede op)
      (assoc target :object/superseded-by (first (:replacement operation)))

      :else target)))

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
