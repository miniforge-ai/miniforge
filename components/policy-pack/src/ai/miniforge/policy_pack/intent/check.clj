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
(ns ai.miniforge.policy-pack.intent.check
  "Full semantic intent check (N4 §4) — composes
   `ai.miniforge.policy-pack.intent/infer-intent` and
   `ai.miniforge.policy-pack.intent/intent-matches?` into a single
   pass/fail result with metadata. Split out of
   `ai.miniforge.policy-pack.intent` (rule 210: the combined namespace
   measured 4 real layers, over the budget of 3); intent classification
   and the constraint-violation chain stay in the parent namespace."
  (:require
   [ai.miniforge.policy-pack.intent :as intent]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} semantic-intent-check
  "Full semantic intent validation check function per N4 §4.

   Arguments:
   - declared-intent — Keyword from intent/intent-types
   - counts          — {:creates int :updates int :destroys int}

   Returns:
   - {:passed? bool :violations [...] :inferred-intent keyword :metadata {...}}"
  [declared-intent counts]
  (let [inferred (intent/infer-intent counts)
        result   (intent/intent-matches? declared-intent counts)]
    (assoc result
           :inferred-intent inferred
           :metadata {:declared declared-intent
                      :counts   counts})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (semantic-intent-check :import {:creates 0 :updates 0 :destroys 0})
  ;; => {:passed? true :inferred-intent :refactor :metadata {...}}

  :leave-this-here)
