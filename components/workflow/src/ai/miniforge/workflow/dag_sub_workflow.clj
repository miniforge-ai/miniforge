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
(ns ai.miniforge.workflow.dag-sub-workflow
  "Per-task sub-workflow construction, split out of `dag-orchestrator`
   (rule 210 — a 1810-line namespace with a non-monotonic layer stack,
   miniforge#1317). Running the constructed sub-workflow to completion
   lives in the sibling `dag-task-execution` namespace — kept separate
   so this file's own base-branch-resolution → opts chain doesn't stack
   underneath execution's run→translate chain in one file.

   Each DAG task receives a full sub-workflow pipeline (explore → plan
   → implement → verify → ...) rather than just an implementer agent.
   The sub-workflow is derived from the parent workflow config, with
   the plan phase skipped (the plan already exists) and DAG execution
   disabled (to prevent infinite recursion). Owns building that
   sub-workflow's config/input/opts, including v1 single-parent and v2
   multi-parent base-branch resolution."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.workflow.dag-merge :as dag-merge]
   [ai.miniforge.workflow.dag-merge-anomaly :as merge-anomaly]
   [ai.miniforge.workflow.dag-plan :as dag-plan]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} task-sub-workflow
  "Build a sub-workflow config for a single DAG task.

   Derives pipeline from the parent workflow, removing explore/plan phases
   (the plan already exists — we're executing it). Keeps :release so each
   DAG task produces its own PR. Strips :observe (parent handles monitoring)."
  [task-def context]
  (let [parent-workflow (:execution/workflow context)
        parent-pipeline (get parent-workflow :workflow/pipeline [])
        sub-phases (->> parent-pipeline
                        (remove #(#{:explore :plan :observe} (:phase %)))
                        vec)
        sub-pipeline (if (seq sub-phases)
                       sub-phases
                       [{:phase :implement} {:phase :release} {:phase :done}])]
    {:workflow/id (keyword (str "dag-task-" (:task/id task-def)))
     :workflow/version "2.0.0"
     :workflow/name (str "DAG sub-task: " (subs (str (:task/description task-def "task"))
                                                0 (min 60 (count (str (:task/description task-def "task"))))))
     :workflow/pipeline sub-pipeline}))

(defn ^{:stratum 0} task-sub-input
  "Build input map for a DAG task's sub-workflow.

   The task description becomes the spec description, and the task itself
   is passed as the plan (single-task plan) so the implement phase can
   pick it up directly. Includes task title and acceptance criteria for
   use by the release phase (PR title/body).

   The 2-arity form threads PR-provenance from the orchestrator context: a
   sub-task's PR frontmatter shares the parent RUN's id and spec path (so all
   PRs from one workflow map back to it), distinguished by :task/id."
  ([task-def] (task-sub-input task-def nil))
  ([task-def context]
   (cond-> {:title (:task/description task-def "Implement task")
            :description (:task/description task-def "Implement task")
            :task/type (:task/type task-def :implement)
            :task/acceptance-criteria (:task/acceptance-criteria task-def [])
            :task/id (:task/id task-def)
            ;; Provide the task as a single-task plan so the implement phase
            ;; receives it without needing another plan phase
            :plan/tasks [{:task/id (random-uuid)
                          :task/description (:task/description task-def "Implement task")
                          :task/type (:task/type task-def :implement)}]}
     (:task/exclusive-files task-def)
     (assoc :files-in-scope (:task/exclusive-files task-def))
     (:task/component task-def)
     (assoc :task/component (:task/component task-def))
     ;; PR provenance: parent run id + source spec, shared across the DAG's
     ;; PRs; :task/id (above) distinguishes them.
     (get-in context [:execution/input :spec/path])
     (assoc :spec/path (get-in context [:execution/input :spec/path]))
     (get context :execution/id)
     (assoc :workflow/parent-id (get context :execution/id)))))

(defn- ^{:stratum 0} resolve-task-base-branch
  "Look up the branch task-def's scratch worktree should be forked from.
   Returns either a branch name string (the resolved base) or an anomaly
   map (multi-parent / non-forest).

   When `:dag/branch-registry` is absent on context (test scaffolding
   that didn't bother building one), behaves exactly as if an empty
   registry were on context — root tasks resolve to the spec branch,
   single-dep tasks fall back to the spec branch (defensive: scheduler
   ordering should prevent this in production). The previous
   'no-registry → omit :branch' path was deliberately removed: it
   reproduced the pre-chaining bug (every task forks off the same
   base), and there's no production caller that hits it — `execute-dag-loop`
   always installs a registry."
  [context task-def]
  (let [registry (some-> (get context :dag/branch-registry) deref)
        deps (vec (or (:task/deps task-def) []))
        default (dag-plan/default-spec-branch context)]
    (dag/resolve-base-branch (or registry (dag/create-branch-registry))
                             deps default)))

(defn- ^{:stratum 0} resolve-task-pr-base-branch
  "Branch a task's release PR should target (its base) — the dep's PUSHED
   branch, so a chained task's PR stacks on the parent's published branch.
   Distinct from `resolve-task-base-branch` (the local worktree fork point).
   Falls back to the spec branch for root / not-yet-released / multi-dep
   tasks. Always returns a branch string (never an anomaly)."
  [context task-def]
  (let [registry (some-> (get context :dag/branch-registry) deref)
        deps (vec (or (:task/deps task-def) []))
        default (dag-plan/default-spec-branch context)]
    (dag/resolve-pr-base-branch (or registry (dag/create-branch-registry))
                                deps default)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} task-sub-opts
  "Build execution opts for a DAG task's sub-workflow.

   Carries forward LLM backend and event stream from parent context.
   Disables DAG execution to prevent recursion and skips lifecycle events
   (parent workflow owns those).

   When `task-def` is supplied, resolves its dependency to a persisted
   branch and passes it as `:branch` so the sub-workflow's
   `acquire-environment!` forks off that branch instead of the spec
   branch:
   - Zero deps or single-dep unregistered → spec branch (`:branch` set).
   - Single-dep registered → dep's branch (v1 chaining payoff;
     `:branch` set).
   - Multi-dep (v2), merge succeeds → the merged ref produced by
     `merge-parent-branches!` (`:branch` set).
   - Multi-dep (v2), merge produces a typed anomaly (conflict /
     unrelated histories / branch unresolvable / strategy unsupported)
     → `:branch` is OMITTED and the anomaly is surfaced via
     `:dag/merge-anomaly` on opts. `run-mini-workflow` checks for this
     and short-circuits with a structured failure rather than running a
     doomed sub-workflow against a stale base.

   The single-arity form `(task-sub-opts context)` is for callers that
   don't yet thread task-def (kept for compatibility with the
   workflow-runner adapter). It does NOT pass `:branch`.

   IMPORTANT: Does NOT pass the parent's executor, environment-id, or
   worktree-path. Each sub-workflow acquires its own isolated environment
   via run-pipeline's acquire-execution-environment!. This prevents:
   - Concurrent sub-workflows from writing to the same directory
   - Stale/broken files from previous runs polluting the release commit
   - Pre-commit hooks picking up unrelated changes from sibling tasks"
  ([context]
   (task-sub-opts context nil))
  ([context task-def]
   (let [deps (vec (or (:task/deps task-def) []))
         base-result (cond
                       (nil? task-def)
                       nil

                       (dag/multi-parent? deps)
                       (dag-merge/merge-parent-branches! context task-def)

                       :else
                       ;; v1 single-parent path returns a branch string for
                       ;; 0/1-dep tasks; lift to dag/ok so downstream
                       ;; branching has a single shape (success → dag/ok,
                       ;; failure → anomaly map). If the v1 path ever
                       ;; produces a map (legacy multi-parent anomaly via
                       ;; stratum auto-wiring or similar), pass it through
                       ;; unchanged so merge-error? still catches it.
                       (let [s (resolve-task-base-branch context task-def)]
                         (if (string? s)
                           (dag/ok {:branch s})
                           s)))
         resolved-branch (when (and base-result (dag/ok? base-result))
                           (:branch (:data base-result)))
         ;; The PR base is the dep's PUSHED branch (a chained task stacks on
         ;; the parent's published mf/... branch), NOT the local worktree
         ;; fork point above. Root tasks resolve to the spec branch → base
         ;; unchanged. The release-executor fetches this and degrades to the
         ;; default if it can't be fetched.
         ;; Only for an actual task (DAG path). The single-arity
         ;; `(task-sub-opts context)` passes task-def=nil (adapter / non-DAG
         ;; callers) — leave :release/base-branch unset there so the release
         ;; phase keeps its default-branch behavior (no forced override/fetch).
         pr-base-branch (when task-def (resolve-task-pr-base-branch context task-def))
         merge-anomaly (when (merge-anomaly/merge-error? base-result) base-result)
         base-opts {:disable-dag-execution true
                    :skip-lifecycle-events true
                    :quiet (boolean (get-in context [:execution/opts :quiet]))
                    :create-pr? true}]
     (cond-> base-opts
       (:llm-backend context)      (assoc :llm-backend (:llm-backend context))
       (:event-stream context)     (assoc :event-stream (:event-stream context))
       (get-in context [:execution/opts :event-stream])
       (assoc :event-stream (get-in context [:execution/opts :event-stream]))
       resolved-branch (assoc :branch resolved-branch)
       pr-base-branch  (assoc :release/base-branch pr-base-branch)
       merge-anomaly  (assoc :dag/merge-anomaly merge-anomaly)))))
