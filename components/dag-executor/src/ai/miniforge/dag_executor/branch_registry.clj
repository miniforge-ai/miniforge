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
   Layer 2: Forest validation (informational helpers; the v1 plan-time
            gate is dropped in v2 — multi-parent DAGs are now the
            normal path, see I-DAG-MULTI-PARENT-MERGE.md)
   Layer 3: Multi-parent base resolution primitives (v2 — pure-data
            inputs to the orchestrator's git-using `merge-parent-branches!`)"
  (:require [ai.miniforge.messages.interface :as messages]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

;------------------------------------------------------------------------------ Translator
;; All user-facing strings — even ones that only ever flow into anomaly
;; payloads or logs — go through the message catalog so they can be
;; localized later without touching code.

(def ^:private t
  (messages/create-translator
    "config/dag-executor/branch_registry/messages/en-US.edn"
    :dag-executor.branch-registry/messages))

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
   :anomaly/message  (t :resolve/multi-parent)
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
       :anomaly/message  (t :validate/multi-parent)
       :multi-parent-tasks violations})))

(defn forest?
  "Predicate form of `validate-forest`. True when the DAG is a forest."
  [tasks]
  (nil? (validate-forest tasks)))

;------------------------------------------------------------------------------ Layer 3
;; Multi-parent base resolution primitives (v2)
;;
;; These are pure-data inputs to the orchestrator's git-using
;; `merge-parent-branches!`. The split is deliberate: ancestor collapse
;; needs git (`git merge-base --is-ancestor`) and lives in the
;; orchestrator; everything before that lives here.
;;
;; See specs/informative/I-DAG-MULTI-PARENT-MERGE.md §3.2 for the full
;; algorithm.

(defn multi-parent?
  "True when `task-deps` declares more than one dependency.

   Callers gate on this BEFORE choosing between `resolve-base-branch`
   (single-parent fast path, returns a branch string) and
   `resolve-multi-parent-base` (returns the v2 ordered-parents shape)."
  [task-deps]
  (> (count task-deps) 1))

(defn resolve-multi-parent-base
  "Build the ordered, SHA-pinned parent list for a multi-parent task.

   Returns:

       {:merge/parents [{:task/id <id> :branch <name> :sha <sha> :order N}]}

   Parents appear in `task-deps` declaration order (the user-controlled
   ordering source per spec §3.1). The orchestrator's git layer is
   responsible for ancestor collapse (which needs `git merge-base
   --is-ancestor`); this function returns the full pre-collapse list.

   Any dep not registered yet is skipped — same fail-soft posture as
   `resolve-base-branch`'s single-parent path. The orchestrator can
   detect this via the `:order` indices: if the count of returned
   parents differs from `(count task-deps)`, some deps were missing,
   and the orchestrator can either fall back to the spec branch or
   surface an anomaly per its policy.

   Returns nil when zero deps would resolve (caller should use the
   single-parent fast path before calling)."
  [registry task-deps]
  (let [resolved (->> task-deps
                      (map-indexed (fn [order task-id]
                                     (when-let [info (lookup-branch registry task-id)]
                                       {:task/id task-id
                                        :branch  (:branch info)
                                        :sha     (:sha info)
                                        :order   order})))
                      (keep identity)
                      vec)]
    (when (seq resolved)
      {:merge/parents resolved})))

(defn collapse-duplicate-tips
  "Drop later parents whose `:sha` matches an earlier parent's `:sha`.

   Returns:

       {:parents   [<surviving parents in original order>]
        :collapsed [{:dropped <task-id> :duplicate-of <task-id>}]}

   `:order` indices on the surviving parents are unchanged so callers
   can detect collapse by comparing input/output counts.

   Treats nil `:sha` as 'unknown' — never collapses against an
   unknown SHA. Callers that want strict collapse should ensure SHAs
   are populated first."
  [parents]
  (loop [remaining parents
         seen-shas {}                   ; sha → task-id (the absorber)
         survivors (transient [])
         collapsed (transient [])]
    (if-let [p (first remaining)]
      (let [sha (:sha p)]
        (if (and (some? sha) (contains? seen-shas sha))
          (recur (rest remaining)
                 seen-shas
                 survivors
                 (conj! collapsed {:dropped       (:task/id p)
                                   :duplicate-of  (get seen-shas sha)}))
          (recur (rest remaining)
                 (if (some? sha) (assoc seen-shas sha (:task/id p)) seen-shas)
                 (conj! survivors p)
                 collapsed)))
      {:parents   (persistent! survivors)
       :collapsed (persistent! collapsed)})))

(defn- hex-digest
  "SHA-256 hex digest of a UTF-8-encoded string. Used for input-key."
  [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) bs))))

(defn compute-input-key
  "Deterministic idempotency hash for a multi-parent merge.

   Per spec §3.2 step 6, the key is computed from
   `[task-id, strategy, ordered parent SHAs]`. Same inputs always
   produce the same key; different inputs always produce different
   keys (probabilistically — SHA-256 collision resistance).

   Used to name the merge ref:
   `refs/miniforge/dag-base/<run-id>/<task-id>/<input-key>`. Replays of
   the same effective input reuse the same ref instead of accumulating
   new ones.

   Truncated to 16 hex chars (64 bits) — enough collision resistance
   for the per-task ref namespace, short enough to read in logs."
  [task-id strategy parents]
  (let [canonical (str (pr-str task-id) "|"
                       (pr-str strategy) "|"
                       (str/join "," (map :sha parents)))]
    (subs (hex-digest canonical) 0 16)))

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
