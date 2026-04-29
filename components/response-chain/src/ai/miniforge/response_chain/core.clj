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

(ns ai.miniforge.response-chain.core
  "Implementation of the response-chain primitive.

   A response-chain is a linear accumulator threaded through a logical
   flow. The functions here are pure: each takes a chain and returns a
   new chain. The component is itself a boundary helper for
   data-first error flow, so it never throws on runtime input — invalid
   input is recorded as an `:invalid-input` anomaly step and the chain
   marches on with `:succeeded?` flipped false."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response-chain.contract :as contract]))

;------------------------------------------------------------------------------ Layer 0
;; Step construction

(defn- build-step
  "Build a canonical step map. `an` may be nil. The step's
   `:succeeded?` is true iff `an` is nil."
  [operation an response]
  {:operation  operation
   :succeeded? (nil? an)
   :anomaly    an
   :response   response})

;------------------------------------------------------------------------------ Layer 1
;; Chain construction

(defn create-chain
  "Return a fresh chain for the named `operation`.

   An empty chain has no steps; its `:succeeded?` is true by vacuous
   truth (every step in the empty set succeeded)."
  [operation]
  {:operation      operation
   :succeeded?     true
   :response-chain []})

;------------------------------------------------------------------------------ Layer 2
;; Append

(defn- recompute-succeeded?
  "Return true iff every step in `steps` has `:succeeded?` true."
  [steps]
  (every? :succeeded? steps))

(defn- conj-step
  "Append `step` to `chain` and recompute the chain's `:succeeded?`
   invariant from the resulting step vector."
  [chain step]
  (let [steps (conj (:response-chain chain) step)]
    (assoc chain
           :response-chain steps
           :succeeded? (recompute-succeeded? steps))))

(defn- guarded-append
  "Append `step` to `chain` after validating both inputs against the
   schema. If validation fails we still produce a step — but it carries
   an `:invalid-input` anomaly and flips the chain's `:succeeded?` false.
   This keeps the component a non-throwing boundary helper."
  [chain operation step]
  (cond
    (not (contract/valid-chain? chain))
    (conj-step (create-chain operation)
               (build-step operation
                           (anomaly/anomaly :invalid-input
                                            "response-chain/append-step received malformed chain"
                                            {:explain (contract/explain-chain chain)})
                           chain))

    (not (contract/valid-step? step))
    (conj-step chain
               (build-step operation
                           (anomaly/anomaly :invalid-input
                                            "response-chain/append-step received malformed step"
                                            {:explain (contract/explain-step step)})
                           step))

    :else
    (conj-step chain step)))

(defn append-step
  "Append a step to `chain` for `operation`.

   With three arguments: a successful step carrying `response`.
   With four arguments: a step carrying `anomaly` (which may be nil) and
   `response`. When `anomaly` is non-nil, the step's `:succeeded?` is
   false and the chain's `:succeeded?` is recomputed accordingly.

   Returns the updated chain. Never throws: malformed inputs are
   recorded as `:invalid-input` anomaly steps."
  ([chain operation response]
   (append-step chain operation nil response))
  ([chain operation an response]
   (guarded-append chain operation (build-step operation an response))))

;------------------------------------------------------------------------------ Layer 3
;; Predicate

(defn succeeded?
  "True when `step-or-chain` is recorded as successful.

   - A step succeeds iff `:succeeded?` is true.
   - A chain succeeds iff every appended step succeeded (an empty chain
     is vacuously successful)."
  [step-or-chain]
  (boolean (:succeeded? step-or-chain)))

;------------------------------------------------------------------------------ Layer 4
;; Accessors

(defn steps
  "Return the vector of step maps from `chain`."
  [chain]
  (get chain :response-chain []))

(defn last-response
  "Return the `:response` of the most recently appended step, or nil
   when the chain has no steps."
  [chain]
  (some-> chain :response-chain peek :response))

(defn last-anomaly
  "Return the `:anomaly` of the most recently appended step, or nil
   when the chain has no steps or the last step succeeded."
  [chain]
  (some-> chain :response-chain peek :anomaly))

(defn last-successful-or
  "Return the `:response` of the most recently appended *successful*
   step, or `default` when the chain has no successful steps."
  [chain default]
  (let [step (->> (steps chain)
                  (filter :succeeded?)
                  last)]
    (if step
      (:response step)
      default)))
