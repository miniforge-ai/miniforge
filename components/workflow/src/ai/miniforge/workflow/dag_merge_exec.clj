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
(ns ai.miniforge.workflow.dag-merge-exec
  "Runs one merge attempt for v2 multi-parent merge (miniforge#1317
   split of `dag-merge`): the `:git-merge` and `:sequential-merge`
   strategies (spec §4), and classifying a failed `git merge` exit
   code as a conflict (resolvable) vs. a fatal infrastructure error
   (not). No caching, no ref-writing, no resolution-sub-workflow
   dispatch — those are the caller's job."
  (:require
   [ai.miniforge.workflow.dag-merge-anomaly :as anomaly]
   [ai.miniforge.workflow.dag-merge-git :as merge-git]
   [ai.miniforge.response.interface :as response]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} enumerate-conflicts
  "Parse `git ls-files --unmerged` output into a per-path summary
   `[{:path <path> :stages [<stage>...]}]`.

   `git ls-files --unmerged` emits one line per stage entry per
   conflicted path (typically stages 1/2/3 = base/ours/theirs); we
   collapse to one entry per path with the observed stages so the
   resolution sub-workflow (Stage 2) sees each conflicted path once
   alongside which stages git surfaced for it."
  [worktree-path]
  (let [r (merge-git/run-git worktree-path "ls-files" "--unmerged")
        lines (when (zero? (:exit r))
                (->> r :out str str/split-lines (remove str/blank?)))]
    (->> lines
         (map anomaly/parse-unmerged-line)
         anomaly/summarize-conflicts-by-path)))

(defn ^{:stratum 0} head-sha-or-anomaly
  "After a merge invocation succeeds, read HEAD and return either a
   response/success with the commit sha or the head-read-failed
   anomaly. Shared between :git-merge and :sequential-merge happy
   paths. Uses `head-read-failed-anomaly` (not `ref-write-failed-anomaly`)
   because no ref write has been attempted yet — the misuse would
   mislead operators with an irrelevant message and nil ref/sha
   fields."
  [worktree-path task-id]
  (let [head (merge-git/run-git worktree-path "rev-parse" "HEAD")]
    (if (zero? (:exit head))
      (response/success {:commit-sha (str/trim (:out head))} nil)
      (anomaly/head-read-failed-anomaly task-id head))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} merge-failure-anomaly
  "Classify a non-zero `git merge` exit code:
   - If the index has unmerged entries, the merge produced conflict
     markers — return the conflict-anomaly so the resolution loop
     can attempt to fix it.
   - Otherwise, git failed for an infrastructure reason (fatal
     error, dirty worktree, etc.) — return the merge-fatal anomaly
     so the operator sees the real cause without it being masked
     by the resolution loop's generic unresolvable terminal.

   `git merge`'s exit code is documented as 1 for conflicts but
   other versions/conditions can return non-zero without conflicts;
   the unmerged-index check is the reliable signal."
  [worktree-path task-id strategy parents input-key git-result]
  (let [conflicts (enumerate-conflicts worktree-path)]
    (if (seq conflicts)
      (anomaly/conflict-anomaly task-id strategy parents conflicts input-key git-result)
      (anomaly/merge-fatal-anomaly task-id strategy parents input-key git-result))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-git-merge!
  "The :git-merge strategy: a single git merge invocation against the
   collapsed parent set. Selects ort for 2 effective parents, octopus
   for 3+. Spec §4.1."
  [worktree-path task-id parents input-key strategy]
  (let [rest-parents (rest parents)
        message (anomaly/deterministic-merge-message task-id parents)
        merge-args (concat ["merge" "-s" (anomaly/merge-strategy-name parents)]
                           (anomaly/pinned-merge-flags message)
                           (map :commit-sha rest-parents))
        r (apply merge-git/run-git worktree-path merge-args)]
    (if (zero? (:exit r))
      (head-sha-or-anomaly worktree-path task-id)
      (merge-failure-anomaly worktree-path task-id strategy parents
                             input-key r))))

(defn ^{:stratum 2} run-sequential-merge!
  "The :sequential-merge strategy: pairwise merges in plan-declaration
   order. Each step is a two-parent `ort` merge. Spec §4.2.

   Returns response/success on the full chain (with HEAD's sha after
   all parents merged) or the conflict anomaly on the first step that
   conflicts. The conflict anomaly's :merge/strategy is preserved as
   :sequential-merge so downstream consumers see what was actually
   attempted."
  [worktree-path task-id parents input-key strategy]
  (let [pairwise-parents (rest parents)
        total-steps      (count pairwise-parents)]
    (loop [remaining pairwise-parents
           step 1]
      (if-not (seq remaining)
        ;; All steps committed cleanly; HEAD is the final merge commit.
        (head-sha-or-anomaly worktree-path task-id)
        (let [parent (first remaining)
              message (anomaly/sequential-step-message task-id step total-steps parent)
              merge-args (concat ["merge" "-s" "ort"]
                                 (anomaly/pinned-merge-flags message)
                                 [(:commit-sha parent)])
              r (apply merge-git/run-git worktree-path merge-args)]
          (if (zero? (:exit r))
            (recur (rest remaining) (inc step))
            (merge-failure-anomaly worktree-path task-id strategy parents
                                   input-key r)))))))
