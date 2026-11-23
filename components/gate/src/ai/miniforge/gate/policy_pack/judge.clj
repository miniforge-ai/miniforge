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
(ns ai.miniforge.gate.policy-pack.judge
  "Wires the batched LLM judge that the policy pack's semantic detector
   calls."
  (:require
   [ai.miniforge.gate.policy-pack.artifact :as artifact]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.semantic-analyzer.interface :as semantic]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} batched-semantic-analyzer
  "An `analyze-rule`-shaped fn (repo-path ignored) backed by ONE batched judge
   call per changed file across ALL `acting-rules`. Memoizes the batch in a
   per-evaluation atom, so the N per-rule semantic check-fn invocations in a
   single gate run share one set of LLM calls; each call returns its own rule's
   slice. Collapses the per-rule-per-file judge into one call per file."
  [acting-rules files]
  (let [cache (atom nil)]
    (fn [llm-client complete-fn _repo-path rule]
      (let [result (or @cache
                       (reset! cache (semantic/analyze-rules-on-files
                                      llm-client complete-fn acting-rules files)))]
        {:rule/id    (:rule/id rule)
         :violations (get-in result [:by-rule (:rule/id rule)] [])
         :status     (:status result)}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-semantic-wiring
  "Inject the LLM-judge seam the policy-pack semantic detector needs. Derives
   `:llm-client` from the run's `:llm-backend` when absent, sets `:complete-fn`
   to `llm/complete`, and injects a `:semantic-analyze-fn` that runs the batched
   judge (one call per changed file across `acting-rules`) over `artifact`'s
   changed files. Also normalizes `:repo-path` (falling back to the execution
   worktree). When the run carries no LLM backend, the seam stays unwired so
   compiled semantic checks fail loud according to pack enforcement
   (fail-closed)."
  [ctx artifact acting-rules]
  (let [ctx (assoc ctx :repo-path (or (:repo-path ctx)
                                      (:execution/worktree-path ctx)
                                      "."))
        ctx (cond-> ctx
              (and (not (:llm-client ctx)) (:llm-backend ctx))
              (assoc :llm-client (:llm-backend ctx))

              (not (:complete-fn ctx))
              (assoc :complete-fn llm/complete))]
    (if (and (:llm-client ctx)
             (:complete-fn ctx)
             (not (:semantic-analyze-fn ctx)))
      (assoc ctx :semantic-analyze-fn (batched-semantic-analyzer
                                       acting-rules (artifact/artifact-changed-files artifact)))
      ctx)))
