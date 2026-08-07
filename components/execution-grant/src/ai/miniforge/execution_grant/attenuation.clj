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
(ns ai.miniforge.execution-grant.attenuation
  "The attenuation rule (Ariadne step 2a, §13.5): you cannot lend what
   you were not lent.

   A delegated grant must be narrower than or equal to its parent on
   EVERY axis — effect class, scope, each ceiling, and expiry. This is
   enforced here in code rather than described in a docstring, because
   an attenuation rule that is only documented is an attenuation rule
   that widens the first time someone is in a hurry.

   Every check states the violation it found rather than returning a
   bare false: `delegate` reports which axis was widened, so a refused
   delegation is diagnosable."
   (:require
   [ai.miniforge.execution-grant.messages :as msg]
   [ai.miniforge.execution-grant.schema :as schema]
   [ai.miniforge.execution-grant.temporal :as temporal]))

;------------------------------------------------------------------------------ Layer 0

;; Axis comparison
(defn ^{:stratum 0} ceiling-widened?
  "True when `child` raises a ceiling above `parent`, or drops a bound
   the parent set.

   An ABSENT axis means unbounded, so a child that omits an axis its
   parent bounds has widened — the most easily-missed case, and the one
   that would hand a delegate an unlimited budget by omission."
  [parent-constraints child-constraints axis]
  (let [p (get parent-constraints axis)
        c (get child-constraints axis)]
    (cond
      (nil? p) false
      (nil? c) true
      :else (> c p))))

(defn ^{:stratum 0} expiry-extended?
  "True when the child outlives its parent. A delegate whose pass
   outlasts the pass it was cut from is authority created out of
   nothing."
  [parent child]
  (let [parent-expiry (temporal/->instant (:grant/expires-at parent))
        child-expiry (temporal/->instant (:grant/expires-at child))]
    (.isAfter child-expiry parent-expiry)))

(defn- ^{:stratum 0} violation
  "Describe one axis on which a child grant widens its parent."
  [axis message-key]
  {:attenuation/axis axis
   :attenuation/detail (msg/t message-key)})

(defn ^{:stratum 0} scope-widened?
  "True when `child-scope` fails to carry every binding `parent-scope`
   set. A child may ADD keys (that narrows); it may not drop or change
   one (that widens).

   `select-keys` preserves a binding to nil while excluding an absent
   key, so it distinguishes those cases without inventing a sentinel
   that could collide with a legitimate `any?` scope value."
  [parent-scope child-scope]
  (not= parent-scope (select-keys child-scope (keys parent-scope))))

;------------------------------------------------------------------------------ Layer 1

;; The rule
(defn ^{:stratum 1} violations
  "All the ways `child` fails to attenuate `parent`, as a vector of
   `{:attenuation/axis ... :attenuation/detail ...}`. Empty means the
   child is a legal narrowing.

   Structural refusals (non-delegable parent, revoked parent) are NOT
   checked here — those are liveness and authority questions that
   `core/delegate` answers before it gets this far."
  [parent child]
  (let [structural
        (cond-> []
          (not= (:grant/effect-class parent) (:grant/effect-class child))
          (conj (violation :grant/effect-class :attenuation/effect-class))

          (scope-widened? (:grant/scope parent) (:grant/scope child))
          (conj (violation :grant/scope :attenuation/scope))

          (expiry-extended? parent child)
          (conj (violation :grant/expires-at :attenuation/expiry)))]
    (into structural
          (comp (filter #(ceiling-widened? (:grant/constraints parent)
                                           (:grant/constraints child)
                                           %))
                (map #(violation % :attenuation/ceiling)))
          schema/constraint-axes)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} attenuates?
  "True when `child` is a legal narrowing of `parent` on every axis."
  [parent child]
  (empty? (violations parent child)))
