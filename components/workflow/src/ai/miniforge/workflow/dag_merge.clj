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
(ns ai.miniforge.workflow.dag-merge
  "v2 multi-parent merge — the public entry point, split out of
   `dag-orchestrator` (rule 210 — a 1810-line namespace with a
   non-monotonic layer stack, miniforge#1317) and then split again
   internally (`dag-merge-git`/`-anomaly`/`-collapse`/`-exec`/
   `-attempt`) once the mechanical layer-budget check (stratum-lint
   SL003) showed the algorithm's own 6-stage pipeline — snapshot →
   dedupe/collapse → shared-ancestry check → merge attempt → conflict
   resolution → ref write — was itself too deep for one file.

   Tasks with `>1` declared dependencies trigger `merge-parent-branches!`,
   which performs a deterministic git merge of the parents' persisted
   branches and hands the resulting ref to the sub-workflow. See
   specs/informative/I-DAG-MULTI-PARENT-MERGE.md. Stage 1B (this code
   path) handles the no-conflict happy path; conflict surfaces as a
   typed anomaly, which Stage 2 will replace with the resolution
   sub-workflow."
  (:require
   [ai.miniforge.workflow.dag-merge-anomaly :as anomaly]
   [ai.miniforge.workflow.dag-merge-attempt :as attempt]
   [ai.miniforge.workflow.dag-merge-git :as merge-git]
   [ai.miniforge.workflow.dag-plan :as dag-plan]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} merge-parent-branches!
  "Multi-parent merge entry point per spec §6.1.

   Inputs:
   - `context` — the orchestrator's per-workflow context (provides host
     repo path, run-id, registry).
   - `task-def` — the multi-parent task being scheduled. Its
     `:task/deps` and (optional) `:task/merge-strategy` drive the work.

   Returns one of:
   - A `dag/ok` result wrapping `{:branch :commit-sha ...}` — merge
     succeeded; downstream task forks off `:branch`. The map carries
     `:single-parent?` (collapse fast-path), `:cache-hit?` (idempotency
     replay), `:fallback-reason` (defensive empty-registry fallback),
     `:collapsed` ([{:dropped :absorbed-into}] for any collapsed
     parents) — callers branch on these for observability but the
     `:branch` is uniformly the right value to use as the sub-workflow
     base.
   - An anomaly map — `:dag-multi-parent-branch-name-invalid` (branch name
     structurally invalid before git is invoked),
     `:dag-multi-parent-conflict`,
     `:dag-multi-parent-unrelated-histories`,
     `:dag-multi-parent-branch-unresolvable`,
     `:dag-multi-parent-strategy-unsupported`,
     `:dag-multi-parent-merge-failed`. Stage 2 will replace the
     conflict path with the resolution sub-workflow; for Stage 1B,
     anomalies surface to the caller and the task fails."
  [context task-def]
  (let [registry  (some-> (get context :dag/branch-registry) deref)
        deps      (vec (or (:task/deps task-def) []))
        task-id   (:task/id task-def)
        strategy  (or (:task/merge-strategy task-def) :git-merge)
        run-id    (or (:workflow-id context) anomaly/fallback-run-id)
        host-repo (merge-git/host-repo-path context)]
    (cond
      (not (contains? anomaly/supported-merge-strategies strategy))
      (anomaly/strategy-unsupported-anomaly task-id strategy)

      :else
      (let [prep (attempt/prepare-effective-parents host-repo registry deps task-id strategy)]
        (cond
          (anomaly/merge-error? prep)
          prep

          (= :no-registered-parents (:fallback prep))
          (anomaly/empty-registry-fallback-result (dag-plan/default-spec-branch context))

          (:single-parent prep)
          (anomaly/single-parent-fast-path-result (:single-parent prep) (:collapsed prep))

          :else
          (attempt/attempt-merge-with-cache! host-repo run-id task-id strategy
                                             (:effective-parents prep)
                                             (:collapsed prep)
                                             ;; Resolution overrides combine the
                                             ;; auto-default agent-edit-fn (built
                                             ;; from `:llm-backend` when present)
                                             ;; with any explicit overrides on
                                             ;; context. Tests inject mocks via
                                             ;; `:dag/resolution-overrides`;
                                             ;; production paths get the real LLM
                                             ;; agent through the auto-default.
                                             {:resolution-overrides
                                              (attempt/derive-resolution-overrides context)}))))))
