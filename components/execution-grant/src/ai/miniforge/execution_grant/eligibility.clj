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
(ns ai.miniforge.execution-grant.eligibility
  "What a principal's breach history permits them to be granted next
   (Ariadne step 2e, §13.5), and the revocation that writes to it.

   The rule here is a POLICY CHOICE, not a derivation. It is stated
   plainly in `permitted-ceiling` so it can be argued with rather than
   absorbed by whoever reads the code next."
  (:require
   [ai.miniforge.execution-grant.breach :as breach]
   [ai.miniforge.execution-grant.core :as core]
   [ai.miniforge.execution-grant.schema :as schema])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} permitted-ceiling
  "The highest ceiling this principal may now be granted on `axis` for
   `effect-class`, or nil for unbounded (no relevant history).

   DAY-ONE RULE, and a policy choice rather than a derivation: a
   principal who blew through a limit may not be handed that limit
   again. The cap becomes the lowest limit they breached. Strictly
   smaller ceilings stay available, so the path back is narrower
   authority rather than a permanent ban.

   What it deliberately does NOT do is decay with time or forgive after
   N clean runs. Both are reasonable; both need a decision this spec
   does not own, and guessing one here would bury it."
  [dir principal effect-class axis]
  (let [limits (into []
                     (comp (filter #(and (= effect-class (:breach/effect-class %))
                                         (= axis (:breach/axis %))))
                           (map :breach/limit))
                     (breach/history dir principal))]
    (when (seq limits) (apply min limits))))

(defn ^{:stratum 0} revoke-for-cause!
  "End a grant BECAUSE of a breach, and remember it.

   Two effects that must not drift apart: the grant carries the cause on
   `:grant/revocation-reason`, and the history carries the numbers —
   which axis, what limit, what was observed, and whether a gate
   prevented it or reconciliation merely found it.

   Revocation is transitive over lineage (2a's `active?` walks
   ancestors), so ending a parent ends everything cut from it. That is
   what makes a delegated inner orchestrator containable rather than
   merely instructed.

   The breach is recorded FIRST. If the history write fails the caller
   must learn the breach went unrecorded, rather than discover a revoked
   grant with no reason behind it."
  [dir grant {:keys [axis limit observed detection]} ^Instant now]
  (let [b {:breach/id (random-uuid)
           :breach/principal (:grant/principal grant)
           :breach/grant-id (:grant/id grant)
           :breach/effect-class (:grant/effect-class grant)
           :breach/axis axis
           :breach/limit limit
           :breach/observed observed
           :breach/detection detection
           :breach/at now}
        reason (case axis
                 :constraint/max-cost-usd :breach/cost-exceeded
                 :constraint/max-tokens :breach/token-exceeded
                 :constraint/max-count :breach/count-exceeded
                 :breach/scope-violation)]
    (breach/record! dir b)
    (core/revoke grant reason now)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} eligible?
  "May this principal be granted `effect-class` at `constraints`?

   False when any requested ceiling reaches or exceeds one they have
   already breached. An unbounded request (axis absent) against a
   principal WITH history on that axis is also refused — asking for no
   limit after breaching a limit is the widest request there is, not an
   abstention."
  [dir principal effect-class constraints]
  (every? (fn [axis]
            (if-let [cap (permitted-ceiling dir principal effect-class axis)]
              (let [requested (get constraints axis)]
                (and (some? requested) (number? requested) (< requested cap)))
              true))
          schema/constraint-axes))
