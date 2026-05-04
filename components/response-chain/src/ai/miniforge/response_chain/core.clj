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
   `:succeeded?` is true iff `an` is nil. When `request` is non-nil it
   is attached as `:request` metadata; when nil the key is omitted so
   the step shape stays minimal for callers that don't need it."
  ([operation an response]
   (build-step operation an response nil))
  ([operation an response request]
   (cond-> {:operation  operation
            :succeeded? (nil? an)
            :anomaly    an
            :response   response}
     (some? request) (assoc :request request))))

;------------------------------------------------------------------------------ Layer 0
;; Operation-key coercion
;;
;; The Chain and Step schemas require `:operation` to be a keyword. The
;; component is a non-throwing boundary helper, so we coerce non-keyword
;; operations to a sentinel and surface the bad input as an anomaly
;; rather than producing a chain that fails its own schema.

(def ^:private invalid-operation-sentinel
  "Sentinel operation key used when caller supplies a non-keyword
   operation. Keeps the chain/step schema invariant intact even when
   input is malformed."
  :response-chain/invalid)

(defn- coerce-operation
  "Return `operation` when it is already a keyword; otherwise return
   the canonical sentinel. Callers use this to ensure the chain/step
   schema is preserved even when input is malformed."
  [operation]
  (if (keyword? operation)
    operation
    invalid-operation-sentinel))

;------------------------------------------------------------------------------ Layer 1
;; Chain construction

(defn create-chain
  "Return a fresh chain for the named `operation`.

   When `operation` is a keyword, the chain is empty and vacuously
   successful. When `operation` is non-keyword, the chain is created
   with the sentinel operation `:response-chain/invalid` and seeded
   with an `:invalid-input` step recording the bad input — preserving
   the schema invariant that every chain/step `:operation` is a
   keyword."
  [operation]
  (let [op (coerce-operation operation)]
    (if (= op operation)
      {:operation      op
       :succeeded?     true
       :response-chain []}
      (let [step (build-step op
                             (anomaly/anomaly
                              :invalid-input
                              "response-chain/create-chain received non-keyword operation"
                              {:operation operation})
                             operation)]
        {:operation      op
         :succeeded?     false
         :response-chain [step]}))))

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

   - 3-arity: a successful step carrying `response`.
   - 4-arity: a step carrying `anomaly` (which may be nil) and
     `response`. When `anomaly` is non-nil, the step's `:succeeded?` is
     false and the chain's `:succeeded?` is recomputed accordingly.
   - 5-arity: same as 4-arity but also carries `request` as `:request`
     metadata on the step. `request` is the input that produced the
     step (e.g. the map a kg-retrieval call received); downstream
     consumers like an inference-evidence bridge can read it to
     reconstruct what was asked. Pass `nil` when no request is
     applicable.

   Returns the updated chain. Never throws: malformed inputs (including
   a non-keyword `operation`) are recorded as `:invalid-input` anomaly
   steps and the chain stays well-formed."
  ([chain operation response]
   (append-step chain operation nil response nil))
  ([chain operation an response]
   (append-step chain operation an response nil))
  ([chain operation an response request]
   (let [op (coerce-operation operation)
         eff-anomaly (if (keyword? operation)
                       an
                       (anomaly/anomaly
                        :invalid-input
                        "response-chain/append-step received non-keyword operation"
                        {:operation         operation
                         :original-anomaly  an}))]
     (guarded-append chain op (build-step op eff-anomaly response request)))))

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
