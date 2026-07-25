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
(ns ai.miniforge.workflow.dag-merge-anomaly
  "Constants, typed-anomaly factories, success-result factories, and the
   pure string/naming helpers for v2 multi-parent merge (miniforge#1317
   split of `dag-merge`). No git I/O, no orchestration — every anomaly
   category the merge pipeline can produce, and the deterministic ref
   names / commit messages / conflict-summary shapes it builds along
   the way."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.workflow.messages :as messages]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} merge-base-ref-prefix
  "Namespace under refs/ where multi-parent merge bases live (spec §7.2)."
  "refs/miniforge/dag-base")

(def ^{:stratum 0} supported-merge-strategies
  "Strategies `merge-parent-branches!` knows how to execute. Per spec
   §4:

   - `:git-merge` (default): one git merge invocation; `ort` for 2
     effective parents, `octopus` for 3+.
   - `:sequential-merge`: pairwise `ort` merges in plan-declaration
     order. Each merge is a two-parent merge (well-characterized);
     handles parent branches that themselves contain merge commits;
     preserves merge-resolution history across iterations. Slower
     than octopus on 3+ parents with no conflicts but easier to
     reason about per-step when conflicts do happen.

   Plans that explicitly request an unsupported strategy get a typed
   anomaly rather than silently falling through to a different
   strategy."
  #{:git-merge :sequential-merge})

(def ^{:stratum 0} octopus-merge-min-parents
  "git's `octopus` strategy is required for 3+ parents. The default
   `ort` strategy handles only 2-head merges. The threshold lives
   here so the comparison sites are self-describing — no magic 2 / 3."
  3)

(def ^{:stratum 0} merge-base-default-max-parents
  "git merge-base without --octopus only handles 2 parents. For 3+
   we pass --octopus to find the n-way common ancestor. Same magic
   number as octopus-merge-min-parents but a different code path,
   named separately so the rationale is explicit at each site."
  2)

(def ^{:stratum 0} fallback-run-id
  "Used when context lacks a workflow-id (test scaffolding mostly).
   The merge ref namespace requires a non-nil run-id segment."
  "no-run-id")

(defn ^{:stratum 0} valid-ref-name?
  "True when `s` is a structurally legal git ref name per git-check-ref-format(1):
   non-empty, no leading dash (flag risk), no whitespace, and none of the
   characters or sequences that git treats as revision operators or prohibits
   in ref names (~, ^, :, ?, *, [, ], \\, .., @{, //)."
  [s]
  (and (string? s)
       (pos? (count s))
       (not (str/starts-with? s "-"))
       (not (re-find #"[\s~\^:\?\*\[\]\\]|\.\.|@\{|//" s))))

;; Anomaly factories ---------------------------------------------------
;; Each factory takes the minimum data needed and produces the canonical
;; anomaly shape (`:anomaly/category` + `:anomaly/message` + rich data
;; under `:merge/*` and `:git/*` keys). All messages route through the
;; workflow message catalog.
(defn ^{:stratum 0} branch-name-invalid-anomaly
  "Anomaly: a parent's branch name is structurally invalid — empty,
   starts with `-` (git flag risk), or contains whitespace. Rejected
   before reaching git so git never receives a potentially malformed
   refspec."
  [parent]
  {:anomaly/category :anomalies/dag-multi-parent-branch-name-invalid
   :anomaly/message  (messages/t :dag.merge/branch-name-invalid)
   :merge/parent     parent})

(defn ^{:stratum 0} branch-unresolvable-anomaly
  "Anomaly: a parent's registered branch could not be rev-parsed in
   the host repo. Usually the registry is out of sync with the repo
   (test scaffolding without the actual branches, or a registry
   carry-over after a force-push that rewrote the branch's tip)."
  [parent git-result]
  {:anomaly/category :anomalies/dag-multi-parent-branch-unresolvable
   :anomaly/message  (messages/t :dag.merge/branch-unresolvable)
   :merge/parent     parent
   :git/exit-code    (:exit git-result)
   :git/stderr       (:err git-result)})

(defn ^{:stratum 0} unrelated-histories-anomaly
  "Anomaly: parents share no common ancestor. v2 refuses to use
   --allow-unrelated-histories per spec §6.5; this is almost always a
   plan-quality / repo-state issue (stray git init, cross-repo branch)."
  [task-id strategy parents]
  {:anomaly/category :anomalies/dag-multi-parent-unrelated-histories
   :anomaly/message  (messages/t :dag.merge/unrelated-histories)
   :task/id          task-id
   :merge/parents    parents
   :merge/strategy   strategy})

(defn ^{:stratum 0} conflict-anomaly
  "Anomaly: git merge produced conflicts. Carries the parents,
   conflict paths, strategy, input-key, and raw git diagnostics.
   Stage 2 will use this shape as the resolution-sub-workflow input
   per spec §6.1."
  [task-id strategy parents conflicts input-key git-result]
  {:anomaly/category :anomalies/dag-multi-parent-conflict
   :anomaly/message  (messages/t :dag.merge/conflict)
   :task/id          task-id
   :merge/parents    parents
   :merge/conflicts  conflicts
   :merge/strategy   strategy
   :merge/input-key  input-key
   :git/exit-code    (:exit git-result)
   :git/stderr       (:err git-result)})

(defn ^{:stratum 0} worktree-setup-failed-anomaly
  "Anomaly: couldn't create the temp worktree for the merge attempt.
   Usually a filesystem / git-state issue from a prior crashed run."
  [task-id git-result]
  {:anomaly/category :anomalies/dag-multi-parent-merge-failed
   :anomaly/message  (messages/t :dag.merge/merge-failed-worktree)
   :task/id          task-id
   :git/exit-code    (:exit git-result)
   :git/stderr       (:err git-result)})

(defn ^{:stratum 0} ref-write-failed-anomaly
  "Anomaly: merge succeeded but `git update-ref` for the namespaced
   ref failed. Surfacing this matters because returning a 'success'
   result with an unresolvable :merge/ref would mislead downstream
   tasks."
  [task-id ref-name commit-sha git-result]
  {:anomaly/category :anomalies/dag-multi-parent-merge-failed
   :anomaly/message  (messages/t :dag.merge/merge-failed-ref-write)
   :task/id          task-id
   :merge/ref        ref-name
   :merge/commit-sha commit-sha
   :git/exit-code    (:exit git-result)
   :git/stderr       (:err git-result)})

(defn ^{:stratum 0} head-read-failed-anomaly
  "Anomaly: merge succeeded but `git rev-parse HEAD` failed. Distinct
   from `ref-write-failed-anomaly` because no ref write has been
   attempted yet (the merge commit exists in HEAD but we couldn't
   read it back to write the namespaced ref). No `:merge/ref` /
   `:merge/commit-sha` fields — they don't exist at this point."
  [task-id git-result]
  {:anomaly/category :anomalies/dag-multi-parent-merge-failed
   :anomaly/message  (messages/t :dag.merge/merge-failed-head-read)
   :task/id          task-id
   :git/exit-code    (:exit git-result)
   :git/stderr       (:err git-result)})

(defn ^{:stratum 0} merge-fatal-anomaly
  "Anomaly: `git merge` exited non-zero AND there are no unmerged
   entries in the index — meaning git failed for an infrastructure
   reason (dirty worktree, repo corruption, fatal error) rather than
   producing conflict markers. Routing this through the conflict-
   resolution loop would mask the original cause; surface as a
   merge-failed anomaly so the operator sees the real problem."
  [task-id strategy parents input-key git-result]
  {:anomaly/category :anomalies/dag-multi-parent-merge-failed
   :anomaly/message  (messages/t :dag.merge/merge-failed-fatal
                                 {:git-exit (:exit git-result)})
   :task/id          task-id
   :merge/parents    parents
   :merge/strategy   strategy
   :merge/input-key  input-key
   :git/exit-code    (:exit git-result)
   :git/stderr       (:err git-result)})

(defn ^{:stratum 0} merge-error?
  "True when a value is one of our merge anomalies (the canonical
   shape with :anomaly/category). Lets callers branch on the result
   without coupling to specific categories.

   Public: `dag-sub-workflow`'s `task-sub-opts` uses this to detect a
   raw (unwrapped) anomaly map coming back from `merge-parent-branches!`
   or the v1 single-parent path — distinct from `dag/err?`, which
   checks the dag-executor result-monad shape these anomalies are
   deliberately NOT wrapped in."
  [x]
  (and (map? x) (some? (:anomaly/category x))))

;; Result factories ----------------------------------------------------
;; Successful merge outcomes go through the existing dag/ok result-monad
;; constructor so consumers can use the same dag/ok? / dag/unwrap
;; predicates that the rest of the orchestrator already uses for task
;; results. The inner shape is consistent across the three success
;; varieties (full merge / single-parent fast-path / fallback) so the
;; consumer only needs to look for :branch and (optionally) :commit-sha.
(defn ^{:stratum 0} merge-ok-result
  "Successful merge commit on the namespaced ref. Includes the
   input-key and observability flags. `:resolved?` and
   `:resolution-iterations` are set when the success came via the
   resolution sub-workflow (Stage 2B+); absent for direct-merge
   successes."
  [{:keys [ref-name commit-sha input-key strategy parents collapsed
           cache-hit? resolved? resolution-iterations]}]
  (dag/ok (cond-> {:branch       ref-name
                   :commit-sha   commit-sha
                   :input-key    input-key
                   :strategy     strategy
                   :parents      parents
                   :cache-hit?   (boolean cache-hit?)}
            (seq collapsed)             (assoc :collapsed collapsed)
            resolved?                   (assoc :resolved? true)
            resolution-iterations       (assoc :resolution-iterations
                                               resolution-iterations))))

(defn ^{:stratum 0} single-parent-fast-path-result
  "Successful resolution where collapse left exactly one effective
   parent — no merge commit needed; downstream task forks off the
   surviving parent's branch directly. Spec §6.2 / §6.3."
  [survivor collapsed]
  (dag/ok (cond-> {:branch         (:branch survivor)
                   :commit-sha     (:commit-sha survivor)
                   :single-parent? true}
            (seq collapsed) (assoc :collapsed collapsed))))

(defn ^{:stratum 0} empty-registry-fallback-result
  "Successful (defensive) resolution where the registry has no parents
   for the declared deps. Mirrors the v1 single-parent path's
   fail-soft semantics; production scheduling should prevent this."
  [default-branch]
  (dag/ok {:branch         default-branch
           :single-parent? true
           :fallback-reason :no-registered-parents}))

(defn ^{:stratum 0} pinned-merge-flags
  "Spec §3.1 — flags that protect the merge from config drift. Without
   these, user-level `commit.gpgsign=true`, merge hooks, etc. would
   change merge behavior in surprising ways across machines."
  [message]
  ["--no-edit" "--no-gpg-sign" "--no-verify" "--no-ff" "-m" message])

(defn ^{:stratum 0} format-parent-line
  "Format one line of the merge commit message, naming the parent's
   declaration order, task-id, and commit SHA."
  [index parent]
  (messages/t :dag.merge/commit-message-parent
              {:index      index
               :task-id    (:task/id parent)
               :commit-sha (:commit-sha parent)}))

(defn ^{:stratum 0} sequential-step-message
  "Per-step commit message for the :sequential-merge strategy. Each
   pairwise merge gets its own commit message so `git log` reads as a
   sequence of named integration steps rather than identical headers."
  [task-id step total parent]
  (let [header (messages/t :dag.merge/sequential-step-header
                           {:step  step
                            :total total
                            :task-id task-id})
        body   (messages/t :dag.merge/sequential-step-body
                           {:parent-task-id (:task/id parent)
                            :parent-sha     (:commit-sha parent)})]
    (str header "\n\n" body)))

(defn ^{:stratum 0} temp-merge-worktree-path
  "Spec §7.2 step 4: ephemeral worktree path scoped by run-id /
   task-id / input-key so concurrent merges across sibling tasks
   never share a directory."
  [run-id task-id input-key]
  (str (System/getProperty "java.io.tmpdir")
       "/miniforge-merge/" run-id "/" task-id "/" input-key))

(defn ^{:stratum 0} parse-unmerged-line
  "Parse one line of `git ls-files --unmerged` output:
   `<mode> <sha> <stage>\\t<path>` → `{:path <path> :stage <stage>}`."
  [line]
  (let [[head path] (str/split line #"\t" 2)
        [_mode _sha stage] (str/split head #"\s+")]
    {:path path :stage stage}))

(defn ^{:stratum 0} summarize-conflicts-by-path
  "Collapse a sequence of `{:path :stage}` entries to one map per
   unique path, with `:stages` carrying the observed stage values."
  [entries]
  (->> entries
       (group-by :path)
       (mapv (fn path-summary [[path es]]
               {:path path :stages (mapv :stage es)}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} strategy-unsupported-anomaly
  "Anomaly: plan requested a merge strategy this stage doesn't
   implement. Echoes the requested strategy and the supported set so
   the operator/dashboard knows what's available right now."
  [task-id strategy]
  {:anomaly/category :anomalies/dag-multi-parent-strategy-unsupported
   :anomaly/message  (messages/t :dag.merge/strategy-unsupported
                                 {:strategy strategy})
   :task/id          task-id
   :merge/strategy   strategy
   :merge/supported  supported-merge-strategies})

;; Naming / formatting helpers ------------------------------------------
(defn ^{:stratum 1} merge-base-ref-name
  "Spec §7.2 step 3. Namespaced ref isolating the merge by run-id,
   task-id, and input-key (so retries of the same effective input
   reuse the same ref instead of accumulating new ones)."
  [run-id task-id input-key]
  (str merge-base-ref-prefix "/" run-id "/" task-id "/" input-key))

(defn ^{:stratum 1} deterministic-merge-message
  "Generate the merge commit message. Includes task-id and ordered
   parent task-ids so the commit is self-describing in `git log`."
  [task-id parents]
  (let [header (messages/t :dag.merge/commit-message-header
                           {:task-id task-id})
        parent-lines (->> parents
                          (map-indexed format-parent-line)
                          (str/join "\n"))]
    (str header "\n\n" parent-lines)))

(defn ^{:stratum 1} needs-octopus-strategy?
  "True when the merge has enough parents that git's `octopus`
   strategy is required (vs. the default `ort` 2-head merge)."
  [parents]
  (>= (count parents) octopus-merge-min-parents))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} merge-strategy-name
  "Git strategy keyword (string) for the n-way merge: `octopus` for
   3+ parents, `ort` otherwise."
  [parents]
  (if (needs-octopus-strategy? parents) "octopus" "ort"))
