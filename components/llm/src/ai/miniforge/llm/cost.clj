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

(ns ai.miniforge.llm.cost
  "Fallback USD cost estimation from token usage when the upstream
   CLI doesn't surface `total_cost_usd` in its streaming result.

   Surfaced by the 2026-05-10 dogfood: a workflow that consumed
   25k+ tokens reported `Cost: $0.0000` because the claude CLI
   version in use omitted total_cost_usd from its result frame.
   Per-token pricing is published data; this namespace consults a
   small EDN table and computes the estimate as the fallback.

   Pricing data lives in `components/llm/resources/llm/cost-table.edn`
   so it can be audited / updated without touching code. Models
   absent from the table estimate as $0.00 — cleaner default than
   throwing for free / local / unpriced backends."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;; Constants

(def ^:private cost-table-resource-path
  "Classpath path to the EDN pricing table. Single source of truth
   so callers can't drift on which file is canonical."
  "llm/cost-table.edn")

(def ^:private tokens-per-million
  "Per-1M-token denominator for the EDN pricing table. Defined as
   a named constant so the unit-of-pricing is readable inline at
   estimate-cost rather than appearing as a bare 1000000 in the
   arithmetic."
  1000000)

(def ^:private cost-table
  "Delay-loaded pricing table. Loaded once at namespace init via
   the resource path; nil-safe when the resource is missing
   (returns an empty pricing map so callers see $0.00 rather than
   throwing)."
  (delay
    (when-let [res (io/resource cost-table-resource-path)]
      (-> res slurp edn/read-string :pricing/by-model-id))))

;; Public API

(defn pricing-for-model
  "Return `{:input-per-1m N :output-per-1m N}` for the given
   model-id string, or nil when the model isn't in the table.
   Callers fold the nil to a zero-cost estimate rather than
   throwing — see estimate-cost."
  [model-id]
  (when (string? model-id)
    (get @cost-table model-id)))

(defn estimate-cost
  "Compute USD cost from a token-usage map and model-id.

   Inputs:
   - usage    — `{:input-tokens N :output-tokens N}` shape (the
                same one parse-claude-stream-line's :usage emits).
                nil-safe — missing token counts default to 0.
   - model-id — string identifying the model (e.g.
                \"claude-sonnet-4-6\"). Models not in the cost
                table estimate to 0.00.

   Returns: a double USD cost. Returns 0.0 for unpriced models
   and for nil/empty usage — never throws, never returns nil, so
   downstream merge-with + and cost summing stay safe."
  [usage model-id]
  (let [{:keys [input-per-1m output-per-1m]} (pricing-for-model model-id)
        in-tokens  (or (:input-tokens usage) 0)
        out-tokens (or (:output-tokens usage) 0)]
    (if (and input-per-1m output-per-1m)
      (+ (* in-tokens  (/ input-per-1m  tokens-per-million))
         (* out-tokens (/ output-per-1m tokens-per-million)))
      0.0)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Look up pricing for a known model
  (pricing-for-model "claude-sonnet-4-6")
  ;; => {:input-per-1m 3.0 :output-per-1m 15.0}

  ;; Unknown model returns nil
  (pricing-for-model "not-a-real-model")
  ;; => nil

  ;; Estimate cost
  (estimate-cost {:input-tokens 10000 :output-tokens 2000}
                 "claude-sonnet-4-6")
  ;; => 0.06  ; 10000 * (3/1M) + 2000 * (15/1M) = 0.03 + 0.03

  ;; Unknown model → 0.00
  (estimate-cost {:input-tokens 10000 :output-tokens 2000}
                 "free-local-model")
  ;; => 0.0

  :leave-this-here)
