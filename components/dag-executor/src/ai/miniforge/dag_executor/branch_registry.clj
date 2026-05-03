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

(ns ai.miniforge.dag-executor.branch-registry
  "Per-task branch registry for DAG sub-workflows.

   Maps `task-id → branch-info` so a downstream task's scratch worktree can
   be acquired off its dependency's persisted branch instead of the spec
   branch. Closes the gap surfaced by the 2026-04-30 dogfood: 6 DAG tasks
   ran with declared dependencies but each was acquired off the same base,
   so 4 of them independently rewrote `components/classification/engine.clj`
   because no agent ever saw the prior task's output.

   Pure data + pure functions. The atom that holds the registry lives in
   the orchestrator's per-workflow context, not here — the orchestrator
   does the single `(swap! reg register-branch ...)` per completed task,
   from one helper (`register-batch-branches!` in dag-orchestrator),
   which keeps mutation confined to one call site and simplifies
   reasoning about concurrent sub-workflows.

   Layer 0: Pure registry operations (create / register / lookup)
   Layer 1: Resolution (turn a task's dep set into a base-branch decision)
   Layer 2: Forest validation (reject multi-parent DAGs at plan time)")

;------------------------------------------------------------------------------ Layer 0
;; Registry primitives — value-in / value-out

(defn create-registry
  "Build an empty registry. The returned value is meant to be wrapped in an
   atom by the orchestrator and threaded on context as
   `:dag/branch-registry`. Per-workflow scope keeps concurrent runs
   isolated — no global state."
  []
  {})

(defn register-branch
  "Record `task-id → branch-info` in the registry.

   `branch-info` is a map with at minimum a `:branch` string. Any extra keys
   (`:commit-sha`, `:bundle-path`, …) flow through unchanged so the
   orchestrator can carry richer provenance without this layer caring.

   Idempotent on re-register: the most recent registration wins. Re-runs of
   the same task-id (rare but possible under retry) replace rather than
   accumulate."
  [registry task-id branch-info]
  (assoc registry task-id branch-info))

(defn lookup-branch
  "Return the `branch-info` map for `task-id`, or nil when unknown.

   nil is meaningful: it indicates the task hasn't completed yet (either
   genuinely upstream pending, or the prior task failed without persisting).
   Resolution treats nil as 'no dependency met' and falls back to the
   default branch."
  [registry task-id]
  (get registry task-id))

;------------------------------------------------------------------------------ Layer 1
;; Resolution — dep set → base branch decision

(def ^:private dag-non-forest-anomaly
  :anomalies/dag-non-forest)

(defn- non-forest-anomaly
  "Build the canonical anomaly map for a multi-parent task.

   Returned as data (not thrown) at the resolve boundary so the
   orchestrator can short-circuit the workflow with the same anomaly
   shape downstream consumers (evidence bundle, dashboard) already
   expect from other anomaly emitters."
  [task-deps]
  {:anomaly/category dag-non-forest-anomaly
   :anomaly/message  "Task has multiple dependencies; linearize or split"
   :task/dependencies (vec task-deps)})

(defn resolve-base-branch
  "Decide which branch a task's scratch worktree should be forked from.

   Three cases:
   - Zero deps: return `default-branch` (the spec branch). Backward-
     compatible — root tasks behave exactly as before this registry
     existed.
   - One dep: return the dep's `:branch` from the registry, or
     `default-branch` if the dep hasn't completed yet (treat 'not yet
     persisted' the same as 'no dependency' rather than blocking — the
     scheduler is responsible for ordering).
   - Multiple deps: return the `:anomalies/dag-non-forest` anomaly map.
     The orchestrator surfaces this at plan-validation time so callers
     can linearize or split; v1 explicitly excludes multi-parent DAGs
     until a follow-up settles on a merge strategy."
  [registry task-deps default-branch]
  (let [deps (vec task-deps)]
    (cond
      (zero? (count deps))
      default-branch

      (= 1 (count deps))
      (or (:branch (lookup-branch registry (first deps)))
          default-branch)

      :else
      (non-forest-anomaly deps))))

(defn resolve-error?
  "True for `resolve-base-branch` outputs that represent an anomaly rather
   than a usable branch name. Lets callers branch on the result without
   coupling to the anomaly shape."
  [resolved]
  (and (map? resolved)
       (= dag-non-forest-anomaly (:anomaly/category resolved))))

;------------------------------------------------------------------------------ Layer 2
;; Plan-time forest validation — reject non-forests before any task runs

(defn- multi-parent-tasks
  "Walk a task seq and pick out any task with more than one dependency.

   Each entry in the returned vector carries enough provenance for the
   error message (`:task/id`, `:dep-count`, `:dependencies`) so the user
   can locate the offending node in the plan."
  [tasks]
  (->> tasks
       (keep (fn [task]
               (let [deps (vec (:task/dependencies task))]
                 (when (> (count deps) 1)
                   {:task/id (:task/id task)
                    :dep-count (count deps)
                    :dependencies deps}))))
       vec))

(defn validate-forest
  "Return nil when the dependency graph is a forest (every task has ≤1
   parent), or an `:anomalies/dag-non-forest` anomaly map listing the
   offending tasks otherwise.

   Called by the orchestrator before any task starts so non-forest plans
   fail loud at plan-validation time rather than after some tasks have
   already burned tokens. v1 single-parent restriction; multi-parent
   merge strategy belongs in a follow-up spec.

   `tasks` is the sequence of plan task maps, each with at minimum
   `:task/id` and `:task/dependencies`."
  [tasks]
  (let [violations (multi-parent-tasks tasks)]
    (when (seq violations)
      {:anomaly/category dag-non-forest-anomaly
       :anomaly/message  "Plan has tasks with multiple dependencies; v1 only supports single-parent forests"
       :multi-parent-tasks violations})))

(defn forest?
  "Predicate form of `validate-forest`. True when the DAG is a forest."
  [tasks]
  (nil? (validate-forest tasks)))

;------------------------------------------------------------------------------ Rich Comment
(comment

  (-> (create-registry)
      (register-branch :a {:branch "task-a"})
      (register-branch :b {:branch "task-b" :commit-sha "abc"}))
  ;; => {:a {:branch "task-a"}, :b {:branch "task-b" :commit-sha "abc"}}

  (resolve-base-branch (register-branch (create-registry) :a {:branch "task-a"})
                       [:a]
                       "main")
  ;; => "task-a"

  (resolve-base-branch (create-registry) [] "main")
  ;; => "main"  (zero deps)

  (resolve-base-branch (create-registry) [:a] "main")
  ;; => "main"  (single dep, not yet registered, falls back)

  (resolve-base-branch (create-registry) [:a :b] "main")
  ;; => {:anomaly/category :anomalies/dag-non-forest ...}

  (validate-forest [{:task/id :a :task/dependencies []}
                    {:task/id :b :task/dependencies [:a]}
                    {:task/id :c :task/dependencies [:a]}])
  ;; => nil — tree is a forest

  (validate-forest [{:task/id :a :task/dependencies []}
                    {:task/id :b :task/dependencies [:a]}
                    {:task/id :c :task/dependencies [:a :b]}])
  ;; => {:anomaly/category :anomalies/dag-non-forest
  ;;     :multi-parent-tasks [{:task/id :c :dep-count 2 :dependencies [:a :b]}]}

  :leave-this-here)
