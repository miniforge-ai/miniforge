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

(ns ai.miniforge.pr-lifecycle.conflict-resolution
  "Spec §6.4 hook: when a PR transitions MERGEABLE→NON_MERGEABLE
   (CONFLICTING with its base branch), invoke the multi-parent merge
   resolution sub-workflow to produce a clean two-parent merge, push
   the resolution commit to the PR branch, and let GitHub re-evaluate
   mergeability.

   The resolution sub-workflow itself lives in
   workflow.merge-resolution; calling it directly from here would
   create a workflow → pr-lifecycle → workflow dependency cycle.
   Stage 3's later slice exposes a public entry point that takes the
   resolver as an injected fn so the workflow-side caller passes
   workflow.merge-resolution/resolve-conflict! at the call site.

   Stage 3 slice 3a (this file): pure-data classifiers and the
   conflict-input builder. Slice 3b adds the git-plumbing helpers
   (extract-conflict-paths, push-resolution!). Slice 3c adds the
   resolve-pr-conflicts! orchestrator that ties them together. Slice
   3d wires the orchestrator into the attempt-merge path so the
   lifecycle automatically engages on a CONFLICTING state."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [babashka.process :as process]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; State classification

(def conflict-marker-re
  "Heuristic match for GitHub `mergeable` and `mergeStateStatus`
   fields when the PR is CONFLICTING with its base. We match both
   the lowercase `mergeable: \"CONFLICTING\"` and the
   `mergeStateStatus: \"DIRTY\"` shapes — GitHub reports DIRTY when
   the merge would conflict. CLEAN / HAS_HOOKS are treated as
   non-conflicting; everything we can't confidently classify falls
   into :unknown so the caller chooses what to do (the safe default
   is don't auto-resolve)."
  #"(?i)CONFLICTING|\"mergeStateStatus\"\s*:\s*\"DIRTY\"")

(defn classify-merge-state
  "Parse GitHub's mergeable / mergeStateStatus JSON output into a
   simple keyword. Inputs come from `gh pr view --json
   mergeable,mergeStateStatus`. Returns one of:
   - :mergeable     — clean, OK to merge
   - :conflicting   — CONFLICTING with base; resolution can engage
   - :unknown       — GitHub hasn't computed yet, or anything else

   Conservative on classification: only emit :conflicting when we
   see a clear conflict signal. UNKNOWN/PENDING stay :unknown so
   the caller polls again rather than running the resolution
   sub-workflow on a state GitHub itself isn't sure about."
  [gh-output]
  (cond
    (or (nil? gh-output) (str/blank? gh-output)) :unknown
    (re-find conflict-marker-re gh-output) :conflicting
    (re-find #"(?i)\"mergeable\"\s*:\s*\"MERGEABLE\"" gh-output) :mergeable
    :else :unknown))

;------------------------------------------------------------------------------ Layer 0
;; Worktree probes (Stage 3b)

(defn- run-cmd
  [cwd args]
  (try
    (let [r (apply process/shell
                   {:dir cwd :out :string :err :string :continue true}
                   args)]
      (if (zero? (:exit r))
        (dag/ok {:output (str/trim (:out r ""))})
        (dag/err :command-failed (str/trim (:err r ""))
                 {:exit-code (:exit r) :args args})))
    (catch Exception e
      (dag/err :command-exception (.getMessage e) {:args args}))))

(defn extract-conflict-paths
  "Run `git diff --name-only --diff-filter=U` in `worktree-path` to
   list paths the index has marked unmerged. Returns a vector of
   relative path strings (possibly empty), or a dag/err if git
   itself failed.

   Spec §6.1 conflict-input requires this list — the resolution
   agent reads each path's content (via build-resolution-task in
   workflow.merge-resolution) so the LLM can see what to fix."
  [worktree-path]
  (let [r (run-cmd worktree-path
                   ["git" "diff" "--name-only" "--diff-filter=U"])]
    (if (dag/ok? r)
      (let [out (:output (:data r))]
        (dag/ok (if (str/blank? out)
                  []
                  (vec (str/split-lines out)))))
      r)))

(defn rev-parse
  "Resolve a ref to a full SHA in `worktree-path`. Wraps `git
   rev-parse <ref>` and returns the trimmed SHA via dag/ok or a
   dag/err on failure."
  [worktree-path ref]
  (let [r (run-cmd worktree-path ["git" "rev-parse" ref])]
    (if (dag/ok? r)
      (dag/ok {:sha (:output (:data r))})
      r)))

(defn push-resolution!
  "Push the worktree's current HEAD to origin/<pr-branch> via plain
   `git push`. The resolution commit is a merge commit on top of
   the PR branch's existing HEAD (parents = [PR-HEAD base-HEAD]),
   so the push is a fast-forward update of the PR branch — no
   force-push needed.

   Plain push rejects non-fast-forwards by default, which is the
   right safety: if upstream moved out from under us between merge
   attempt and push, the conflict-input we resolved against is
   stale and we'd want to re-evaluate rather than overwrite the
   new state.

   Returns dag/ok `{:pushed-sha <sha>}` on success, or the dag/err
   from the underlying git push on failure."
  [worktree-path pr-branch]
  (let [push-r (run-cmd worktree-path
                        ["git" "push" "origin"
                         (str "HEAD:" pr-branch)])]
    (if (dag/ok? push-r)
      (let [sha-r (rev-parse worktree-path "HEAD")]
        (if (dag/ok? sha-r)
          (dag/ok {:pushed?    true
                   :pushed-sha (:sha (:data sha-r))
                   :pr-branch  pr-branch})
          sha-r))
      push-r)))

;------------------------------------------------------------------------------ Layer 1
;; conflict-input shape

(defn build-pr-conflict-input
  "Assemble the conflict-input map that workflow.merge-resolution/
   resolve-conflict! expects, with the two-parent shape from spec
   §6.4: PR branch + PR base. Order is fixed (PR branch first, base
   second) so replays produce the same input-key.

   Inputs:
   - `pr` — `{:pr/id :pr/branch :pr/base :pr/head-sha :pr/base-sha}`.
   - `conflict-paths` — vector of relative paths from the worktree.

   The :merge/conflicts entries carry a `:stages` placeholder
   (`[\"1\" \"2\" \"3\"]` — pre-merge / ours / theirs) consistent
   with how the dag-orchestrator emits them; build-resolution-prompt
   joins them with '/' for display so the agent sees a stable
   shape."
  [{:pr/keys [id branch base head-sha base-sha]} conflict-paths]
  {:task/id          (str "pr-" id)
   :merge/parents    [{:task/id    :pr-branch
                       :branch     branch
                       :commit-sha head-sha
                       :order      0}
                      {:task/id    :pr-base
                       :branch     base
                       :commit-sha base-sha
                       :order      1}]
   :merge/strategy   :git-merge
   :merge/input-key  (str "pr-" id "-" head-sha "-" base-sha)
   :merge/conflicts  (mapv (fn [p] {:path p :stages ["1" "2" "3"]})
                           conflict-paths)})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Classify a gh pr view output
  (classify-merge-state "{\"mergeable\":\"CONFLICTING\",\"mergeStateStatus\":\"DIRTY\"}")
  ;; => :conflicting

  ;; Build the conflict-input shape
  (build-pr-conflict-input
   {:pr/id 123 :pr/branch "feat/x" :pr/base "main"
    :pr/head-sha "abc1234" :pr/base-sha "def5678"}
   ["src/conflict.txt"])

  :leave-this-here)
