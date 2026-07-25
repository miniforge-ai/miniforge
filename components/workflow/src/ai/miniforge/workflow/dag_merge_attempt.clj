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
(ns ai.miniforge.workflow.dag-merge-attempt
  "Idempotency, worktree lifecycle, and conflict-resolution dispatch
   around one merge attempt (miniforge#1317 split of `dag-merge`):
   check the namespaced ref cache first (spec §7.2), otherwise stage a
   temp worktree, run the merge, write the ref, and — on conflict —
   spawn the resolution sub-workflow (spec §6.1) before giving up.
   `merge-parent-branches!` (in `dag-merge` itself) is the only
   caller; this is where the actual git-worktree side effects live."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.dag-merge-anomaly :as anomaly]
   [ai.miniforge.workflow.dag-merge-collapse :as collapse]
   [ai.miniforge.workflow.dag-merge-exec :as merge-exec]
   [ai.miniforge.workflow.dag-merge-git :as merge-git]
   [ai.miniforge.workflow.merge-resolution :as merge-resolution]
   [babashka.fs :as fs]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} ensure-clean-worktree!
  "Remove any pre-existing temp worktree at `path` and re-create it
   fresh from `commit-sha`. The merge ref namespace already de-dupes
   replays, but if a prior crash left state on disk we want a clean
   slate, not a half-merged residual."
  [host-repo worktree-path commit-sha]
  (try (fs/delete-tree worktree-path) (catch Throwable _ nil))
  (merge-git/run-git host-repo "worktree" "prune")
  (when-let [parent-dir (.getParent (java.io.File. ^String worktree-path))]
    (.mkdirs (java.io.File. ^String parent-dir)))
  (merge-git/run-git host-repo "worktree" "add" "--detach" worktree-path commit-sha))

(defn ^{:stratum 0} cleanup-worktree!
  "Best-effort cleanup of the merge temp worktree. Errors here are
   intentionally swallowed: by the time we're cleaning up, the merge
   has already completed (success or anomaly) and a cleanup failure
   shouldn't mask that result."
  [host-repo worktree-path]
  (try (merge-git/run-git host-repo "worktree" "remove" "--force" worktree-path)
       (catch Throwable _ nil))
  (try (fs/delete-tree worktree-path) (catch Throwable _ nil))
  (merge-git/run-git host-repo "worktree" "prune"))

(defn ^{:stratum 0} existing-merge-ref-sha
  "If the namespaced merge ref already exists from a prior replay,
   return its current SHA; else nil. Used for spec §7.2's
   idempotency: replays of the same effective input MUST reuse the
   same ref instead of producing a new merge commit (whose timestamp
   would differ even though the tree is identical, defeating the
   cache)."
  [host-repo ref-name]
  (let [r (merge-git/run-git host-repo "rev-parse" "--verify" (str ref-name "^{commit}"))]
    (when (zero? (:exit r))
      (str/trim (:out r)))))

(defn ^{:stratum 0} run-merge!
  "Invoke the merge strategy in the temp worktree per spec §3.1 / §6.1.
   Returns either:
   - `(response/success {:commit-sha <sha>})` when the merge lands a
     real commit and rev-parse HEAD reports it cleanly.
   - The conflict anomaly map (`:anomalies/dag-multi-parent-conflict`)
     when git's exit code reports a conflict (sequential-merge: on
     the first step that conflicts; git-merge: on the single merge).
   - The merge-failed anomaly map (`:anomalies/dag-multi-parent-merge-failed`)
     when the merge succeeded but rev-parse HEAD failed (rare
     infrastructure case).

   Caller checks `response/success?` for the happy path and dispatches
   on `:anomaly/category` for the failure path."
  [worktree-path task-id strategy parents input-key]
  (case strategy
    :git-merge        (merge-exec/run-git-merge!        worktree-path task-id parents input-key strategy)
    :sequential-merge (merge-exec/run-sequential-merge! worktree-path task-id parents input-key strategy)
    ;; Defensive — supported-merge-strategies guards upstream stop us
    ;; from reaching this branch in normal flow. If we do, surface a
    ;; typed anomaly rather than silently picking a strategy.
    (anomaly/strategy-unsupported-anomaly task-id strategy)))

(defn ^{:stratum 0} prepare-effective-parents
  "Run the spec §3.2 collapse pipeline (snapshot SHAs → dedupe →
   ancestor-collapse) and return one of:

   - `{:fallback :no-registered-parents}` — no deps in registry; caller
     should return `empty-registry-fallback-result`.
   - `{:single-parent <p> :collapsed [...]}` — collapse left one
     effective parent; caller should return `single-parent-fast-path-result`.
   - `{:effective-parents [...] :collapsed [...]}` — ready to merge.
   - An anomaly map — branch unresolvable, unrelated histories.

   Pure pipeline of registry + git; doesn't touch worktrees or refs.
   Keeps `merge-parent-branches!` focused on orchestration."
  [host-repo registry deps task-id strategy]
  (let [resolved (dag/resolve-multi-parent-base
                  (or registry (dag/create-branch-registry))
                  deps)]
    (if-not (seq (:merge/parents resolved))
      {:fallback :no-registered-parents}
      (let [snapshot (collapse/snapshot-parent-shas host-repo (:merge/parents resolved))]
        (if (anomaly/merge-error? snapshot)
          snapshot
          (let [deduped (dag/collapse-duplicate-tips (:parents snapshot))
                {:keys [parents collapsed]} (collapse/collapse-ancestors host-repo (:parents deduped))
                all-collapsed (vec (concat (:collapsed deduped) collapsed))]
            (cond
              (= 1 (count parents))
              {:single-parent (first parents) :collapsed all-collapsed}

              (not (collapse/shared-ancestry? host-repo parents))
              (anomaly/unrelated-histories-anomaly task-id strategy parents)

              :else
              {:effective-parents parents :collapsed all-collapsed})))))))

(defn ^{:stratum 0} write-ref-and-build-success
  "After a merge or resolution lands a commit-sha, write it to the
   namespaced ref and either return the standard merge-success shape
   or the typed ref-write-failed anomaly. Shared between the
   no-conflict happy path and the resolution-success path. `extras`
   may contain :resolved? / :resolution-iterations for the resolution
   path (merge-ok-result destructures them explicitly so they survive)."
  [host-repo task-id ref-name commit-sha input-key strategy parents collapsed extras]
  (let [upd (merge-git/run-git host-repo "update-ref" ref-name commit-sha)]
    (if (zero? (:exit upd))
      (anomaly/merge-ok-result (merge {:ref-name   ref-name
                                       :commit-sha commit-sha
                                       :input-key  input-key
                                       :strategy   strategy
                                       :parents    parents
                                       :collapsed  collapsed}
                                      extras))
      (anomaly/ref-write-failed-anomaly task-id ref-name commit-sha upd))))

(defn ^{:stratum 0} derive-resolution-overrides
  "Build the resolution-overrides map handed to `resolve-conflict!`.
   Combines:
   - explicit overrides on `context` under `:dag/resolution-overrides`
     (tests inject mocks here);
   - an auto-default `agent-edit-fn` built from `agent-driven-edit-fn`
     when `:llm-backend` is on context and the explicit overrides
     don't already specify one.

   Explicit overrides win — production paths get the real LLM agent,
   tests stay free to inject deterministic mocks. With neither
   override nor backend present, the resolution loop falls back to
   the namespace-default no-op stub (preserves Stage 2B behaviour for
   non-LLM contexts)."
  [context]
  (let [explicit (get context :dag/resolution-overrides)
        llm-backend (:llm-backend context)
        auto (when (and llm-backend (not (:agent-edit-fn explicit)))
               {:agent-edit-fn
                (merge-resolution/agent-driven-edit-fn
                 (cond-> {:llm-backend llm-backend}
                   (:logger context) (assoc :logger (:logger context))))})]
    (merge auto explicit)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} attempt-resolution!
  "Spec §6.1 conflict path. The merge produced a conflict; spawn the
   resolution sub-workflow to try to resolve it. On success we land a
   resolution commit, write the namespaced ref, and return the
   standard merge-success shape (with `:resolved?` and `:resolution-
   iterations` for observability). On failure we return the
   `:dag-multi-parent-unresolvable` terminal anomaly.

   Stage 2B's resolution loop uses a no-op stub agent-edit-fn by
   default, so conflicts still terminate as unresolvable — just via
   the loop rather than an immediate anomaly. Stage 2C will inject
   the real LLM-driven implementer."
  [host-repo task-id ref-name input-key strategy parents collapsed
   conflict-anomaly worktree resolution-overrides]
  (let [outcome (merge-resolution/resolve-conflict!
                 (merge {:conflict-input conflict-anomaly
                         :host-repo host-repo
                         :worktree-path worktree
                         :task-id task-id}
                        resolution-overrides))]
    (if (dag/ok? outcome)
      (let [{:keys [commit-sha iterations]} (:data outcome)]
        (write-ref-and-build-success
         host-repo task-id ref-name commit-sha input-key strategy
         parents collapsed
         {:resolved? true :resolution-iterations iterations}))
      ;; Outcome is the unresolvable anomaly itself — pass through.
      outcome)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} attempt-merge-with-cache!
  "Cache-aware merge attempt. Checks for an existing namespaced ref
   first (spec §7.2 idempotency); if present, reuses its SHA. Otherwise
   stages a temp worktree, runs the merge, writes the ref, and cleans
   up. On conflict, spawns the resolution sub-workflow per spec §6.1
   instead of immediately surfacing the conflict — the resolution
   loop's terminal anomaly is what reaches the caller when the agent
   can't make the merge clean.

   Returns a `dag/ok` result on success or an anomaly on terminal
   failure."
  [host-repo run-id task-id strategy parents collapsed
   {:keys [resolution-overrides] :as _opts}]
  (let [input-key (dag/compute-merge-input-key task-id strategy parents)
        ref-name  (anomaly/merge-base-ref-name run-id task-id input-key)
        cached    (existing-merge-ref-sha host-repo ref-name)]
    (if cached
      (anomaly/merge-ok-result {:ref-name   ref-name
                                :commit-sha cached
                                :input-key  input-key
                                :strategy   strategy
                                :parents    parents
                                :collapsed  collapsed
                                :cache-hit? true})
      (let [worktree (anomaly/temp-merge-worktree-path run-id task-id input-key)
            setup    (ensure-clean-worktree! host-repo worktree
                                             (:commit-sha (first parents)))]
        (if-not (zero? (:exit setup))
          (do (cleanup-worktree! host-repo worktree)
              (anomaly/worktree-setup-failed-anomaly task-id setup))
          (let [outcome (run-merge! worktree task-id strategy parents input-key)]
            (cond
              ;; Merge succeeded — outcome is response/success carrying
              ;; the commit-sha.
              (response/success? outcome)
              (let [success (write-ref-and-build-success
                             host-repo task-id ref-name
                             (get-in outcome [:output :commit-sha])
                             input-key strategy parents collapsed nil)]
                (cleanup-worktree! host-repo worktree)
                success)

              ;; Conflict — spawn the resolution sub-workflow on the same
              ;; worktree. The conflict anomaly carries parents, paths,
              ;; strategy, input-key, exit-code, stderr — everything the
              ;; resolution agent (or its mock) needs.
              (= :anomalies/dag-multi-parent-conflict
                 (:anomaly/category outcome))
              (let [result (attempt-resolution!
                            host-repo task-id ref-name input-key strategy
                            parents collapsed
                            outcome worktree resolution-overrides)]
                (cleanup-worktree! host-repo worktree)
                result)

              ;; Non-conflict failure (e.g. rev-parse HEAD failed after a
              ;; successful merge — `:dag-multi-parent-merge-failed`).
              ;; This is an infrastructure error, not a conflict to
              ;; resolve. Surface it directly so the operator sees the
              ;; real cause; routing it through the resolution loop
              ;; would hide the original git failure behind a generic
              ;; `:dag-multi-parent-unresolvable`.
              :else
              (do (cleanup-worktree! host-repo worktree)
                  outcome))))))))
