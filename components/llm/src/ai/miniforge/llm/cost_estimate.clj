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
(ns ai.miniforge.llm.cost-estimate
  "A cost-table estimate only where it can be known (#2378). An estimate
   reads downstream as a measured cost, so it is given only when usage
   reports both input and output token counts, no prompt-cache tokens, the
   model has a price, and the prompt is within the range that price
   covers. Otherwise there is no estimate and the cost stays unknown."
  (:require
   [ai.miniforge.llm.cost :as cost]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private beside-input-cache-keys
  "Prompt-cache counts the Claude CLI reports beside `:input-tokens`.
   `cost/estimate-cost` prices only input and output, so an estimate
   that left them out would be low."
  [:cache-creation-input-tokens :cache-read-input-tokens])

(defn- ^{:stratum 0} within-input-limit?
  "False when `pricing` holds only up to `:max-input-tokens` prompt tokens
   and `input-tokens` exceeds it (Gemini 2.5 Pro bills longer prompts at a
   higher rate the table does not carry)."
  [pricing input-tokens]
  (let [limit (:max-input-tokens pricing)]
    (or (not (number? limit))
        (<= input-tokens limit))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} cached?
  "True when `usage` reports any prompt-cache tokens beside its input."
  [usage]
  (boolean (some (fn [k]
                   (let [n (get usage k)]
                     (and (number? n) (pos? n))))
                 beside-input-cache-keys)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} known-estimate
  "The cost-table estimate for `usage` on `model-id`, or nil when it cannot
   be known: a missing input or output count, prompt-cache tokens, an
   unpriced model, or a prompt beyond the price's range."
  [usage model-id]
  (let [{:keys [input-tokens output-tokens]} usage
        pricing (cost/pricing-for-model model-id)]
    (when (and pricing
               (number? input-tokens)
               (number? output-tokens)
               (not (cached? usage))
               (within-input-limit? pricing input-tokens))
      (cost/estimate-cost usage model-id))))
