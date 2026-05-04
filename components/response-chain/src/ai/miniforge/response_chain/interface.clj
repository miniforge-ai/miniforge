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

(ns ai.miniforge.response-chain.interface
  "Public API for the response-chain component.

   A response-chain is a linear runtime trace plus a data-first error
   primitive. Each participating function appends a step (operation
   keyword + success? + anomaly|response) and the chain becomes the
   record of what happened during a logical request.

   Pairs with `ai.miniforge.anomaly` to give the miniforge family one
   shared error-flow vocabulary: functions return anomalies (data) and
   the chain accumulates them; nothing throws.

   Invariant: a chain's `:succeeded?` is the conjunction of every step's
   `:succeeded?`. An empty chain is vacuously successful. Each
   `append-step` call recomputes this; callers should not modify a
   chain by hand.

   Standard usage:

     (require '[ai.miniforge.response-chain.interface :as chain]
              '[ai.miniforge.anomaly.interface :as anomaly])

     (defn run [user-id]
       (let [c0 (chain/create-chain :auth/login)
             user (db/find user-id)]
         (if (nil? user)
           (chain/append-step c0
                              :db/find-user
                              (anomaly/anomaly :not-found \"missing\" {:id user-id})
                              nil)
           (chain/append-step c0 :db/find-user user))))"
  (:require
   [ai.miniforge.response-chain.contract :as contract]
   [ai.miniforge.response-chain.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def Chain
  "Malli schema for the canonical chain map. See
   `ai.miniforge.response-chain.contract/Chain`."
  contract/Chain)

(def Step
  "Malli schema for a single chain step. See
   `ai.miniforge.response-chain.contract/Step`."
  contract/Step)

;------------------------------------------------------------------------------ Layer 1
;; Construction

(defn create-chain
  "Start a new response-chain for the named `operation-key`.

   Returns a chain map with no steps and `:succeeded?` true (vacuous
   truth — every step in the empty set succeeded)."
  [operation-key]
  (core/create-chain operation-key))

;------------------------------------------------------------------------------ Layer 2
;; Append

(defn append-step
  "Append a step to `chain` for `operation-key`.

   - 3-arity `(append-step chain operation-key response)`
       Append a successful step carrying `response`. The chain's
       `:succeeded?` is unchanged unless an earlier step had failed.

   - 4-arity `(append-step chain operation-key anomaly response)`
       Append a step carrying `anomaly` (which may be nil) and
       `response`. When `anomaly` is non-nil, the step's `:succeeded?`
       is false and the chain's `:succeeded?` is recomputed as the
       conjunction across every step in the resulting chain.

   - 5-arity `(append-step chain operation-key anomaly response request)`
       Same as the 4-arity, plus the original `request` is recorded on
       the step as `:request` metadata. Use this when a downstream
       consumer (e.g. an inference-evidence bridge) needs to
       reconstruct what was asked, not just what came back. Pass `nil`
       to omit the metadata. The `:request` key is intentionally
       absent on steps that don't carry one — chains stay minimal for
       LLM-visible renderings.

   This function never throws. Malformed input is recorded as an
   `:invalid-input` anomaly step rather than raising; the chain remains
   well-formed."
  ([chain operation-key response]
   (core/append-step chain operation-key response))
  ([chain operation-key anomaly response]
   (core/append-step chain operation-key anomaly response))
  ([chain operation-key anomaly response request]
   (core/append-step chain operation-key anomaly response request)))

;------------------------------------------------------------------------------ Layer 3
;; Predicate

(defn succeeded?
  "True when `step-or-chain` is recorded as successful.

   - For a step: returns the step's `:succeeded?` flag.
   - For a chain: true iff every appended step succeeded (empty chains
     are vacuously successful)."
  [step-or-chain]
  (core/succeeded? step-or-chain))

;------------------------------------------------------------------------------ Layer 4
;; Accessors

(defn steps
  "Return the vector of step maps from `chain` in append order."
  [chain]
  (core/steps chain))

(defn last-response
  "Return the `:response` of the most recently appended step, or nil
   when the chain has no steps."
  [chain]
  (core/last-response chain))

(defn last-anomaly
  "Return the `:anomaly` of the most recently appended step, or nil
   when the chain has no steps or the last step succeeded."
  [chain]
  (core/last-anomaly chain))

(defn last-successful-or
  "Return the `:response` of the most recently appended *successful*
   step, or `default` when no successful step has been appended."
  [chain default]
  (core/last-successful-or chain default))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (require '[ai.miniforge.anomaly.interface :as anomaly])

  (def c0 (create-chain :auth/login))
  ;; => {:operation :auth/login :succeeded? true :response-chain []}

  (def c1 (append-step c0 :db/find-user {:id 7 :name "ada"}))
  (succeeded? c1)
  ;; => true
  (last-response c1)
  ;; => {:id 7 :name "ada"}

  (def boom (anomaly/anomaly :not-found "no user" {:id 99}))
  (def c2 (append-step c1 :db/find-user boom nil))
  (succeeded? c2)
  ;; => false
  (last-anomaly c2)
  ;; => boom

  (last-successful-or c2 ::none)
  ;; => {:id 7 :name "ada"}

  :leave-this-here)
