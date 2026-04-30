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

(ns ai.miniforge.response-chain.contract
  "Malli contracts for the response-chain shape.

   A response-chain is a linear runtime trace. Each participating
   function appends a `Step` describing what operation ran, whether it
   succeeded, the response value, and (if it failed) the anomaly that
   was returned. The `Chain` is the accumulating envelope.

   Schemas are exposed so consumers can validate at their own
   boundaries. The component itself only validates input where doing so
   prevents corruption of the chain invariant."
  (:require
   [ai.miniforge.anomaly.contract :as anomaly-contract]
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Step

(def Step
  "Malli schema for a single response-chain step.

   Every step carries:
   - :operation  — keyword identifying the function/operation that ran
   - :succeeded? — true when the step completed without an anomaly
   - :anomaly    — Anomaly map when the step failed; nil otherwise
   - :response   — the value the operation produced (may be nil)"
  [:map {:closed true}
   [:operation  :keyword]
   [:succeeded? :boolean]
   [:anomaly    [:maybe anomaly-contract/Anomaly]]
   [:response   :any]])

;------------------------------------------------------------------------------ Layer 1
;; Chain

(def Chain
  "Malli schema for a response-chain envelope.

   Every chain carries:
   - :operation      — keyword naming the logical flow this chain traces
   - :succeeded?     — conjunction of every step's :succeeded?; true for
                       an empty chain (vacuous truth)
   - :response-chain — vector of `Step` maps in append order"
  [:map {:closed true}
   [:operation      :keyword]
   [:succeeded?     :boolean]
   [:response-chain [:vector Step]]])

;------------------------------------------------------------------------------ Layer 2
;; Validation helpers

(defn valid-step?
  "Return true when `value` matches the `Step` schema."
  [value]
  (m/validate Step value))

(defn valid-chain?
  "Return true when `value` matches the `Chain` schema."
  [value]
  (m/validate Chain value))

(defn explain-step
  "Return a humanized explanation for an invalid step, or nil
   when `value` is valid."
  [value]
  (some-> (m/explain Step value)
          me/humanize))

(defn explain-chain
  "Return a humanized explanation for an invalid chain, or nil
   when `value` is valid."
  [value]
  (some-> (m/explain Chain value)
          me/humanize))
