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

(ns ai.miniforge.workflow.dag-orchestrator
  "Orchestrates parallel task execution via DAG scheduling.

   Supports resumable execution: pass :pre-completed-ids in the context
   to skip tasks that completed in a prior run. Emits :dag/task-completed
   events to the event stream so completed work survives crashes.

   Each DAG task receives a full sub-workflow pipeline (explore → plan → implement
   → verify → ...) rather than just an implementer agent. The sub-workflow is
   derived from the parent workflow config, with the plan phase skipped (the plan
   already exists) and DAG execution disabled (to prevent infinite recursion).

   v2 multi-parent: tasks with `>1` declared dependencies trigger the
   orchestrator's `merge-parent-branches!`, which performs a deterministic
   git merge of the parents' persisted branches and hands the resulting
   ref to the sub-workflow. See specs/informative/I-DAG-MULTI-PARENT-MERGE.md.
   Stage 1B (this code path) handles the no-conflict happy path; conflict
   surfaces as a typed anomaly, which Stage 2 will replace with the
   resolution sub-workflow."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.dag-resilience :as resilience]
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.set :as set]
   [clojure.string :as str]))

;--- Layer 0: Result Constructors

(defn workflow-success [artifact metrics]
  {:success? true
   :artifact artifact
   :metrics (or metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})})

(defn workflow-failure [error metrics]
  {:success? false
   :error error
   :metrics (or metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})})

(defn dag-execution-result [completed failed artifacts metrics-agg & {:keys [unreached] :or {unreached 0}}]
  {:success? (and (zero? failed) (zero? unreached))
   :tasks-completed completed
   :tasks-failed failed
   :artifacts (vec artifacts)
   :metrics {:tokens (:total-tokens metrics-agg 0)
             :cost-usd (:total-cost metrics-agg 0.0)
             :duration-ms (:total-duration metrics-agg 0)}})

(defn dag-execution-error [completed failed error]
  {:success? false
   :tasks-completed completed
   :tasks-failed failed
   :artifacts []
   :metrics {}
   :error error})

(defn dag-execution-paused
  [completed-task-ids failed-task-ids artifacts decision]
  (let [reset-at (:reset-at decision)
        wait-ms (:wait-ms decision)
        auto-resume? (= :checkpoint-and-resume (:action decision))]
    {:success? false
     :paused? true
     :tasks-completed (count completed-task-ids)
     :tasks-failed (count failed-task-ids)
     :completed-task-ids (vec completed-task-ids)
     :artifacts (vec artifacts)
     :pause-reason (:reason decision)
     :reset-at reset-at
     :wait-ms wait-ms
     :auto-resume? auto-resume?
     :metrics {}}))

;--- Layer 0: Level Traversal

(defn build-deps-map [tasks]
  (->> tasks
       (map (fn [t] [(:task/id t) (set (:task/dependencies t []))]))
       (into {})))

(defn traverse-levels [task-ids deps-map]
  (loop [remaining (set task-ids)
         completed #{}
         level-count 0
         max-width 0]
    (if (empty? remaining)
      {:levels level-count :max-width max-width}
      (let [ready (->> remaining
                       (filter #(every? completed (get deps-map % #{}))))
            width (count ready)]
        (recur (apply disj remaining ready)
               (into completed ready)
               (inc level-count)
               (max max-width width))))))

(defn compute-max-level-width [tasks]
  (-> tasks
      ((juxt #(map :task/id %) build-deps-map))
      ((fn [[ids deps]] (traverse-levels ids deps)))
      :max-width))

;--- Layer 0: Plan Analysis

(defn parallelizable-plan? [plan]
  (let [tasks (:plan/tasks plan [])]
    (when (> (count tasks) 1)
      (> (compute-max-level-width tasks) 1))))

(defn estimate-parallel-speedup [plan]
  (let [tasks (:plan/tasks plan [])
        task-count (count tasks)
        deps-map (build-deps-map tasks)
        {:keys [levels max-width]} (traverse-levels (map :task/id tasks) deps-map)]
    {:parallelizable? (> max-width 1)
     :task-count task-count
     :max-parallel max-width
     :levels levels
     :estimated-speedup (if (pos? levels) (float (/ task-count levels)) 1.0)}))

;--- Layer 1: Plan to DAG Conversion

(defn normalize-task-id
  "Preserve task IDs in their domain-native form.
   UUID strings are parsed to UUIDs so mixed string/UUID inputs still align."
  [x]
  (cond
    (uuid? x) x
    (string? x) (or (parse-uuid x) x)
    (keyword? x) x
    :else x))

(defn validate-deps
  "Filter deps to only those referencing actual task IDs. Warns on phantoms."
  [task-id raw-deps valid-task-ids]
  (let [valid (set (filter valid-task-ids raw-deps))
        invalid (remove valid-task-ids raw-deps)]
    (when (seq invalid)
      (println "WARN: Task" task-id
               "has dependencies on non-existent tasks:"
               (vec invalid) "— dropping them"))
    valid))

(defn plan-task->dag-task
  "Convert a single plan task to a DAG task with validated deps."
  [t valid-task-ids plan-id workflow-id context]
  (let [task-id (normalize-task-id (:task/id t))]
    (cond-> {:task/id task-id
             :task/deps (validate-deps task-id
                                       (map normalize-task-id (:task/dependencies t []))
                                       valid-task-ids)
             :task/description (:task/description t)
             :task/type (:task/type t :implement)
             :task/acceptance-criteria (:task/acceptance-criteria t [])
             :task/context (merge {:parent-plan-id plan-id
                                   :parent-workflow-id workflow-id}
                                  (select-keys context [:llm-backend :artifact-store]))}
      (:task/component t)      (assoc :task/component (:task/component t))
      (:task/exclusive-files t) (assoc :task/exclusive-files (:task/exclusive-files t))
      (:task/stratum t)         (assoc :task/stratum (:task/stratum t)))))

(defn wire-stratum-deps
  "Auto-wire dependencies from :task/stratum when explicit deps are absent.
   All tasks at stratum N depend on all tasks at stratum N-1.
   No-op when no tasks have :task/stratum set."
  [dag-tasks]
  (if-not (some :task/stratum dag-tasks)
    dag-tasks
    (let [by-stratum (group-by #(:task/stratum % 0) dag-tasks)]
      (mapv (fn [task]
              (let [s (:task/stratum task 0)]
                (if (and (empty? (:task/deps task #{}))
                         (pos? s))
                  (let [prev-ids (set (map :task/id (get by-stratum (dec s) [])))]
                    (assoc task :task/deps prev-ids))
                  task)))
            dag-tasks))))

(defn plan->dag-tasks [plan context]
  (let [tasks (:plan/tasks plan [])
        valid-task-ids (set (map (comp normalize-task-id :task/id) tasks))
        dag-tasks (mapv #(plan-task->dag-task % valid-task-ids (:plan/id plan) (:workflow-id context) context)
                        tasks)]
    (wire-stratum-deps dag-tasks)))

;--- Layer 1: Sub-Workflow Construction

(defn task-sub-workflow
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

(defn task-sub-input
  "Build input map for a DAG task's sub-workflow.

   The task description becomes the spec description, and the task itself
   is passed as the plan (single-task plan) so the implement phase can
   pick it up directly. Includes task title and acceptance criteria for
   use by the release phase (PR title/body)."
  [task-def]
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
    (assoc :task/component (:task/component task-def))))

(defn- default-spec-branch
  "Branch the orchestrator should treat as the spec's parent — the one root
   tasks acquire off and dep-resolution falls back to."
  [context]
  (or (get-in context [:execution/opts :branch])
      (get-in context [:execution/branch])
      "main"))

(defn- resolve-task-base-branch
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
        default (default-spec-branch context)]
    (dag/resolve-base-branch (or registry (dag/create-branch-registry))
                             deps default)))

;--- Layer 1.5: Multi-parent merge (v2)
;; See specs/informative/I-DAG-MULTI-PARENT-MERGE.md §3.2 for the algorithm
;; and §6 for the failure-mode catalog. Stage 1B implements the no-conflict
;; happy path; conflict / unrelated-histories surface as terminal anomalies
;; that Stage 2 will replace with an automated resolution sub-workflow.

(defn- run-git
  "Invoke git in `cwd` with `args`. Returns `{:exit :out :err}` matching
   `clojure.java.shell/sh`. Defensive against shell exceptions so callers
   can branch on `:exit` rather than wrapping in try/catch."
  [cwd & args]
  (try
    (apply shell/sh "git" "-C" cwd args)
    (catch Exception e
      {:exit -1 :out "" :err (.getMessage e)})))

(defn- host-repo-path
  "Resolve the host repository path for orchestrator-level git operations.
   Falls back through the standard context keys orchestrators populate."
  [context]
  (or (get context :execution/repo-path)
      (get-in context [:execution/environment-metadata :repo-path])
      (get context :execution/worktree-path)
      (System/getProperty "user.dir")))

(defn- snapshot-parent-shas
  "Resolve each parent's branch tip to a SHA at this moment (spec §3.2
   step 1). Returns the parents vector with `:commit-sha` populated, or
   an anomaly when any branch can't be resolved."
  [host-repo parents]
  (loop [remaining parents
         out (transient [])]
    (if-let [p (first remaining)]
      (let [r (run-git host-repo "rev-parse" "--verify" (str (:branch p) "^{commit}"))]
        (if (zero? (:exit r))
          (recur (rest remaining)
                 (conj! out (assoc p :commit-sha (str/trim (:out r)))))
          {:anomaly/category :anomalies/dag-multi-parent-branch-unresolvable
           :anomaly/message "Parent branch could not be resolved to a commit"
           :merge/parent p
           :git/exit-code (:exit r)
           :git/stderr (:err r)}))
      {:parents (persistent! out)})))

(defn- ancestor-of?
  "True when `sha-a` is an ancestor of (reachable from) `sha-b`."
  [host-repo sha-a sha-b]
  (zero? (:exit (run-git host-repo "merge-base" "--is-ancestor" sha-a sha-b))))

(defn- collapse-ancestors
  "Spec §3.2 step 4. Drop any parent whose tip is reachable from another
   parent's tip; preserve order among surviving maximal tips. Returns
   `{:parents [...] :collapsed [{:dropped :absorbed-into}]}`."
  [host-repo parents]
  (let [ancestor? (memoize (fn [a b] (ancestor-of? host-repo a b)))
        survivors (filter (fn [p]
                            (not-any? (fn [other]
                                        (and (not= (:task/id other) (:task/id p))
                                             (not= (:commit-sha other) (:commit-sha p))
                                             (ancestor? (:commit-sha p) (:commit-sha other))))
                                      parents))
                          parents)
        survivor-set (set (map :task/id survivors))
        collapsed (->> parents
                       (remove #(contains? survivor-set (:task/id %)))
                       (mapv (fn [dropped]
                               {:dropped (:task/id dropped)
                                :absorbed-into
                                (->> survivors
                                     (filter #(ancestor? (:commit-sha dropped) (:commit-sha %)))
                                     first
                                     :task/id)})))]
    {:parents (vec survivors)
     :collapsed collapsed}))

(defn- shared-ancestry?
  "True when at least one common ancestor exists across all parent SHAs.
   Two-parent case is the common one; for 3+, git's merge-base requires
   `--octopus` to find the n-way common ancestor."
  [host-repo parents]
  (let [shas (mapv :commit-sha parents)
        args (cond-> ["merge-base"]
               (> (count shas) 2) (conj "--octopus")
               :always (into shas))
        r (apply run-git host-repo args)]
    (and (zero? (:exit r))
         (not (str/blank? (:out r))))))

(def ^:private merge-base-ref-prefix "refs/miniforge/dag-base")

(defn- merge-base-ref-name
  "Spec §7.2 step 3. Namespaced ref under `refs/miniforge/dag-base/...`
   isolating the merge by run-id, task-id, and the input-key (so retries
   of the same effective input reuse the same ref)."
  [run-id task-id input-key]
  (str merge-base-ref-prefix "/" run-id "/" task-id "/" input-key))

(defn- pinned-merge-flags
  "Spec §3.1 — flags that protect the merge from config drift. Without
   these, user-level `commit.gpgsign=true`, merge hooks, etc. would
   change merge behavior in surprising ways across machines."
  [message]
  ["--no-edit" "--no-gpg-sign" "--no-verify" "--no-ff" "-m" message])

(defn- deterministic-merge-message
  "Generate the merge commit message. Includes task-id and ordered
   parent task-ids so the commit is self-describing in `git log`."
  [task-id parents]
  (str "miniforge dag-merge for task " task-id "\n\n"
       "Parents (declaration order):\n"
       (str/join "\n"
                 (map-indexed (fn [i p]
                                (format "  %d. %s @ %s"
                                        i (:task/id p) (:commit-sha p)))
                              parents))))

(defn- temp-merge-worktree-path
  [run-id task-id input-key]
  (str (System/getProperty "java.io.tmpdir")
       "/miniforge-merge/" run-id "/" task-id "/" input-key))

(defn- enumerate-conflicts
  "Parse `git ls-files --unmerged` output into a per-path summary
   `[{:path <path> :stages [<stage>...]}]`.

   `git ls-files --unmerged` emits one line per stage entry per
   conflicted path (typically stages 1/2/3 = base/ours/theirs); we
   collapse to one entry per path with the observed stages so the
   resolution sub-workflow (Stage 2) sees each conflicted path once
   alongside which stages git surfaced for it."
  [worktree-path]
  (let [r (run-git worktree-path "ls-files" "--unmerged")
        lines (when (zero? (:exit r))
                (->> (str/split-lines (str (:out r)))
                     (remove str/blank?)))]
    (->> lines
         (map (fn [line]
                ;; format: <mode> <sha> <stage>\t<path>
                (let [[head path] (str/split line #"\t" 2)
                      [_mode _sha stage] (str/split head #"\s+")]
                  {:path path :stage stage})))
         (group-by :path)
         (mapv (fn [[path entries]]
                 {:path path
                  :stages (mapv :stage entries)})))))

(defn- run-merge!
  "Invoke `git merge` in the temp worktree per spec §3.1 / §6.1.
   Returns `{:ok? true :commit-sha ...}` or
   `{:ok? false :anomaly ...}`. Caller is responsible for cleanup."
  [worktree-path host-repo task-id strategy parents input-key]
  (let [[p0 & rest-parents] parents
        message (deterministic-merge-message task-id parents)
        merge-strategy (if (> (count parents) 2) "octopus" "ort")
        merge-args (concat ["merge" "-s" merge-strategy]
                           (pinned-merge-flags message)
                           (map :commit-sha rest-parents))
        r (apply run-git worktree-path merge-args)]
    (cond
      (zero? (:exit r))
      (let [head (run-git worktree-path "rev-parse" "HEAD")]
        (if (zero? (:exit head))
          {:ok? true :commit-sha (str/trim (:out head))}
          {:ok? false
           :anomaly {:anomaly/category :anomalies/dag-multi-parent-merge-failed
                     :anomaly/message "Merge succeeded but rev-parse HEAD failed"
                     :git/exit-code (:exit head)
                     :git/stderr (:err head)}}))

      :else
      {:ok? false
       :anomaly {:anomaly/category :anomalies/dag-multi-parent-conflict
                 :anomaly/message  "Multi-parent merge conflicted"
                 :task/id          task-id
                 :merge/parents    parents
                 :merge/conflicts  (enumerate-conflicts worktree-path)
                 :merge/strategy   strategy
                 :merge/input-key  input-key
                 :git/exit-code    (:exit r)
                 :git/stderr       (:err r)}})))

(defn- ensure-clean-worktree!
  "Remove any pre-existing temp worktree at `path` and re-create it
   fresh from `commit-sha`. The merge ref namespace already de-dupes
   replays, but if a prior crash left state on disk we want a clean
   slate, not a half-merged residual."
  [host-repo worktree-path commit-sha]
  (try (fs/delete-tree worktree-path) (catch Throwable _ nil))
  (run-git host-repo "worktree" "prune")
  (let [parent-dir (.getParent (java.io.File. ^String worktree-path))]
    (when parent-dir (.mkdirs (java.io.File. ^String parent-dir))))
  (run-git host-repo "worktree" "add" "--detach" worktree-path commit-sha))

(defn- cleanup-worktree!
  [host-repo worktree-path]
  (try (run-git host-repo "worktree" "remove" "--force" worktree-path)
       (catch Throwable _ nil))
  (try (fs/delete-tree worktree-path) (catch Throwable _ nil))
  (run-git host-repo "worktree" "prune"))

(def ^:private supported-merge-strategies
  "Strategies `merge-parent-branches!` knows how to execute today.
   `:git-merge` ships in Stage 1B; `:sequential-merge` is Stage 4
   (per the spec §4 / §10.11 strategy table). Plans that explicitly
   request an unsupported strategy get a typed anomaly rather than
   silently falling through to `:git-merge` (which would misreport
   the executed strategy in logs and the resolution-sub-workflow
   payload)."
  #{:git-merge})

(defn- existing-merge-ref-sha
  "If the namespaced merge ref already exists from a prior replay,
   return its current SHA; else nil. Used for spec §7.2's
   idempotency: replays of the same effective input MUST reuse the
   same ref instead of producing a new merge commit (whose timestamp
   would differ even though the tree is identical, defeating the
   cache)."
  [host-repo ref-name]
  (let [r (run-git host-repo "rev-parse" "--verify" (str ref-name "^{commit}"))]
    (when (zero? (:exit r))
      (str/trim (:out r)))))

(defn merge-parent-branches!
  "Multi-parent merge entry point per spec §6.1.

   Inputs:
   - `context` — the orchestrator's per-workflow context (provides host
     repo path, run-id, registry).
   - `task-def` — the multi-parent task being scheduled. Its
     `:task/deps` and (optional) `:task/merge-strategy` drive the work.

   Returns one of:
   - `{:merge/ok? true :merge/ref <ref-name> :merge/commit-sha <sha>}` —
     merge succeeded; downstream task forks off `:merge/ref`.
   - `{:merge/ok? true :merge/ref <branch> :merge/single-parent? true}` —
     after collapse, only one effective parent; downstream task forks off
     that parent's branch directly (no merge commit needed). This is the
     §6.2 / §6.3 informational fast-path.
   - An anomaly map — `:dag-multi-parent-conflict`,
     `:dag-multi-parent-unrelated-histories`,
     `:dag-multi-parent-branch-unresolvable`,
     `:dag-multi-parent-strategy-unsupported`,
     `:dag-multi-parent-merge-failed`. Stage 2 will replace the
     conflict path with the resolution sub-workflow; for Stage 1B,
     anomalies surface to the caller and the task fails."
  [context task-def]
  (let [registry (some-> (get context :dag/branch-registry) deref)
        deps (vec (or (:task/deps task-def) []))
        task-id (:task/id task-def)
        strategy (or (:task/merge-strategy task-def) :git-merge)
        run-id (or (:workflow-id context) "no-run-id")
        host-repo (host-repo-path context)
        resolved (dag/resolve-multi-parent-base
                  (or registry (dag/create-branch-registry))
                  deps)]
    (cond
      ;; Strategy guard — fail fast for unsupported strategies rather
      ;; than silently executing a different one. :sequential-merge
      ;; ships in Stage 4.
      (not (contains? supported-merge-strategies strategy))
      {:anomaly/category :anomalies/dag-multi-parent-strategy-unsupported
       :anomaly/message  (str "Merge strategy " strategy " is not supported in this stage")
       :task/id          task-id
       :merge/strategy   strategy
       :merge/supported  supported-merge-strategies}

      ;; No deps registered — fall back to default branch (matches the
      ;; single-parent fast-path's defensive semantics).
      (not (seq (:merge/parents resolved)))
      {:merge/ok? true
       :merge/ref (default-spec-branch context)
       :merge/single-parent? true
       :merge/fallback-reason :no-registered-parents}

      :else
      (let [snapshot (snapshot-parent-shas host-repo (:merge/parents resolved))]
        (if (:anomaly/category snapshot)
          snapshot
          (let [;; Spec §3.2 step 3 — pure-data dedupe (already in registry layer)
                deduped (dag/collapse-duplicate-tips (:parents snapshot))
                ;; Spec §3.2 step 4 — git-side ancestor collapse
                {:keys [parents collapsed]} (collapse-ancestors host-repo (:parents deduped))
                all-collapsed (vec (concat (:collapsed deduped) collapsed))]
            (cond
              ;; Single effective parent after collapse — no merge needed.
              (= 1 (count parents))
              (cond-> {:merge/ok? true
                       :merge/ref (:branch (first parents))
                       :merge/commit-sha (:commit-sha (first parents))
                       :merge/single-parent? true}
                (seq all-collapsed) (assoc :merge/collapsed all-collapsed))

              ;; No shared ancestry → unrelated-histories anomaly per §6.5.
              (not (shared-ancestry? host-repo parents))
              {:anomaly/category :anomalies/dag-multi-parent-unrelated-histories
               :anomaly/message  "Parents share no common ancestor — refusing to merge"
               :task/id          task-id
               :merge/parents    parents
               :merge/strategy   strategy}

              :else
              (let [input-key (dag/compute-merge-input-key task-id strategy parents)
                    ref-name (merge-base-ref-name run-id task-id input-key)
                    cached-sha (existing-merge-ref-sha host-repo ref-name)]
                (if cached-sha
                  ;; Spec §7.2 idempotency: ref already exists from a
                  ;; prior replay with byte-identical input. Reuse it
                  ;; instead of producing a fresh merge commit (which
                  ;; would have a different timestamp despite the same
                  ;; tree — defeating the cache and accumulating
                  ;; duplicate refs).
                  (cond-> {:merge/ok?         true
                           :merge/ref         ref-name
                           :merge/commit-sha  cached-sha
                           :merge/input-key   input-key
                           :merge/strategy    strategy
                           :merge/parents     parents
                           :merge/cache-hit?  true}
                    (seq all-collapsed) (assoc :merge/collapsed all-collapsed))
                  (let [worktree (temp-merge-worktree-path run-id task-id input-key)
                        setup (ensure-clean-worktree! host-repo worktree (:commit-sha (first parents)))]
                    (if-not (zero? (:exit setup))
                      (do (cleanup-worktree! host-repo worktree)
                          {:anomaly/category :anomalies/dag-multi-parent-merge-failed
                           :anomaly/message  "Could not create temp worktree for merge"
                           :task/id          task-id
                           :git/exit-code    (:exit setup)
                           :git/stderr       (:err setup)})
                      (let [outcome (run-merge! worktree host-repo task-id strategy parents input-key)]
                        (if (:ok? outcome)
                          ;; Capture update-ref's exit code so a failed
                          ;; ref write surfaces as an anomaly rather than
                          ;; a false-positive success.
                          (let [upd (run-git host-repo "update-ref"
                                             ref-name (:commit-sha outcome))]
                            (cleanup-worktree! host-repo worktree)
                            (if (zero? (:exit upd))
                              (cond-> {:merge/ok?        true
                                       :merge/ref        ref-name
                                       :merge/commit-sha (:commit-sha outcome)
                                       :merge/input-key  input-key
                                       :merge/strategy   strategy
                                       :merge/parents    parents}
                                (seq all-collapsed) (assoc :merge/collapsed all-collapsed))
                              {:anomaly/category :anomalies/dag-multi-parent-merge-failed
                               :anomaly/message  "Failed to write merge result to namespaced ref"
                               :task/id          task-id
                               :merge/ref        ref-name
                               :merge/commit-sha (:commit-sha outcome)
                               :git/exit-code    (:exit upd)
                               :git/stderr       (:err upd)}))
                          (do (cleanup-worktree! host-repo worktree)
                              (:anomaly outcome)))))))))))))))

(defn- merge-error?
  "True when `merge-parent-branches!` returned an anomaly rather than
   a usable merge result."
  [result]
  (and (map? result) (:anomaly/category result)))

(defn task-sub-opts
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
                       (merge-parent-branches! context task-def)

                       :else
                       (resolve-task-base-branch context task-def))
         resolved-branch (cond
                           (string? base-result)            base-result
                           (and (map? base-result)
                                (:merge/ok? base-result))   (:merge/ref base-result)
                           :else                            nil)
         merge-anomaly (when (merge-error? base-result) base-result)
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
       merge-anomaly  (assoc :dag/merge-anomaly merge-anomaly)))))

;--- Layer 1: Mini-Workflow Execution

(defn extract-sub-workflow-error
  "Extract the most informative error message from a failed sub-workflow result.
   Digs into phase results to find rate limit messages and other details
   that may not appear in the top-level execution errors.
   Prioritizes rate-limit messages so the DAG orchestrator can detect them."
  [result]
  (let [execution-error (-> result :execution/errors first :message)
        ;; Scan all phase error messages for rate limit indicators
        phase-errors (->> (vals (:execution/phase-results result))
                          (keep #(get-in % [:error :message]))
                          (filter not-empty))
        rate-limit-msg (first (filter #(re-find #"(?i)rate.?limit|429|hit your limit|quota.?exceeded"
                                                (str %))
                                      (cons execution-error phase-errors)))]
    ;; Prioritize rate limit messages so DAG can detect and pause
    (or rate-limit-msg
        execution-error
        (first phase-errors)
        "Sub-workflow failed")))

(defn extract-pr-info-from-result
  "Extract PR info from a sub-workflow result if the release phase produced one."
  [result task-def]
  (let [release-result (get-in result [:execution/phase-results :release])
        pr-info (or (get-in release-result [:result :output :workflow/pr-info])
                    ;; Also check metrics path where leave-release stores it
                    (get-in result [:metrics :release :pr-info]))]
    (when pr-info
      (merge pr-info
             {:task-id (:task/id task-def)
              :deps (set (map normalize-task-id (:task/dependencies task-def [])))}))))

(defn- task-result-branch
  "Branch name produced by the sub-workflow's persist step for this task.

   In governed mode the runner sets `:execution/task-branch` directly. In
   local mode the worktree executor's `environment-id` IS the branch name
   created by `git worktree add -b <env-id>`. Either is fine; we pick
   whichever is present, falling back to nil so callers know nothing
   persisted."
  [result]
  (or (:execution/task-branch result)
      (:execution/environment-id result)))

(defn run-mini-workflow
  "Execute a full sub-workflow pipeline for a single DAG task.

   Each task gets its own workflow (implement → verify → review → done)
   derived from the parent workflow config. The plan phase is skipped
   because the task description IS the plan.

   Threads `task-def` to `task-sub-opts` so per-task base chaining can
   resolve the right base branch from the workflow's branch registry —
   downstream tasks acquire off the prior task's persisted branch instead
   of the spec branch. v2 multi-parent path: when `task-def` has 2+
   deps, `task-sub-opts` invokes `merge-parent-branches!` which performs
   a deterministic git merge and returns a `:merge/ref`; the sub-workflow
   forks off that merged base. If the merge produces a typed anomaly
   (conflict / unrelated histories / unresolvable parent), `task-sub-opts`
   surfaces it via `:dag/merge-anomaly` on opts and we fail the task
   here rather than running a doomed sub-workflow.

   Extracts PR info from the release phase result and includes it in the
   workflow result."
  [task-def context]
  (let [sub-workflow (task-sub-workflow task-def context)
        sub-input    (task-sub-input task-def)
        sub-opts     (task-sub-opts context task-def)
        merge-anomaly (:dag/merge-anomaly sub-opts)]
    (if merge-anomaly
      ;; Multi-parent merge failed before we even entered the sub-workflow.
      ;; Surface the FULL typed anomaly map (not just its message) so
      ;; downstream consumers — workflow-result->dag-result, the dashboard,
      ;; the evidence bundle — can classify the failure by
      ;; `:anomaly/category` and access the raw `:merge/conflicts`,
      ;; `:merge/parents`, `:git/stderr` etc. Stage 2 will use this
      ;; structured shape as the resolution sub-workflow's input.
      (workflow-failure merge-anomaly nil)
      (let [run-pipeline (:execution/run-pipeline-fn context)
            result       (run-pipeline sub-workflow sub-input sub-opts)
            artifacts    (:execution/artifacts result)
            metrics      (:execution/metrics result)
            pr-info      (extract-pr-info-from-result result task-def)
            task-branch  (task-result-branch result)]
        (if (phase/succeeded? result)
          (cond-> (workflow-success (first artifacts) metrics)
            pr-info (assoc :pr-info pr-info)
            ;; Carry sub-workflow's worktree path so apply-dag-success can
            ;; merge changes back into the parent worktree for release.
            (:execution/worktree-path result)
            (assoc :worktree-path (:execution/worktree-path result))
            ;; Carry the persisted branch name so the orchestrator can register
            ;; it for downstream tasks' base resolution.
            task-branch (assoc :task-branch task-branch))
          (workflow-failure (extract-sub-workflow-error result) metrics))))))

(defn workflow-result->dag-result [task-id description wf-result]
  (if (:success? wf-result)
    (dag/ok (cond-> {:task-id task-id
                     :description description
                     :status :implemented
                     :artifacts [(:artifact wf-result)]
                     :metrics (:metrics wf-result)}
              (:pr-info wf-result)    (assoc :pr-info (:pr-info wf-result))
              (:task-branch wf-result) (assoc :task-branch (:task-branch wf-result))))
    (dag/err :task-execution-failed
             (:error wf-result)
             {:task-id task-id :metrics (:metrics wf-result)})))

(defn placeholder-result [task-id description]
  (dag/ok {:task-id task-id
           :description description
           :status :implemented
           :artifacts []
           :metrics {:tokens 0 :cost-usd 0.0}}))

(defn execute-single-task [task-def context]
  (let [task-id (:task/id task-def)
        description (:task/description task-def "Implement task")]
    (try
      (if (:llm-backend context)
        (workflow-result->dag-result task-id description (run-mini-workflow task-def context))
        (placeholder-result task-id description))
      (catch InterruptedException ie
        ;; Restore interrupt flag so the cancelled future terminates cleanly
        ;; and the DAG batch cleanup can proceed.
        (.interrupt (Thread/currentThread))
        (dag/err :task-cancelled
                 (str "Task cancelled: " (.getMessage ie))
                 {:task-id task-id}))
      (catch Exception e
        (dag/err :task-execution-failed
                 (str "Task failed: " (.getMessage e))
                 {:task-id task-id})))))

(defn create-task-executor-fn [context opts]
  (let [{:keys [on-task-start on-task-complete]} opts]
    (fn [task-id dag-context]
      (when on-task-start (on-task-start task-id))
      (let [task-def (get-in dag-context [:run-state :run/tasks task-id])
            result (execute-single-task task-def context)]
        (when on-task-complete (on-task-complete task-id result))
        result))))

;--- Layer 2: Synchronous DAG Execution

(defn compute-ready-tasks [tasks-map completed-ids failed-ids]
  (->> tasks-map
       (filter (fn [[task-id task]]
                 (and (not (contains? completed-ids task-id))
                      (not (contains? failed-ids task-id))
                      (every? #(contains? completed-ids %) (:task/deps task #{})))))))

(defn select-non-conflicting-batch
  "Select up to max-parallel ready tasks whose exclusive-files don't overlap.
   Falls back to (take max-parallel) when no tasks declare exclusive-files."
  [ready-tasks max-parallel]
  (loop [candidates (seq ready-tasks)
         selected []
         claimed-files #{}]
    (cond
      (nil? candidates)                  selected
      (>= (count selected) max-parallel) selected
      :else
      (let [[_tid task] (first candidates)
            task-files (set (:task/exclusive-files task))]
        (if (and (seq task-files)
                 (seq (set/intersection claimed-files task-files)))
          (recur (next candidates) selected claimed-files)
          (recur (next candidates)
                 (conj selected (first candidates))
                 (into claimed-files task-files)))))))

(defn execute-tasks-batch
  "Execute a batch of tasks in parallel via futures.
   Ensures all futures are cancelled on failure so that sub-workflow
   finally blocks can release their execution environments (Docker
   containers, worktrees). Without cancellation, abandoned futures
   keep capsules alive until the JVM exits."
  [tasks execute-fn context]
  (let [task-futures (doall
                      (map (fn [[task-id task]]
                             [task-id (future (execute-fn task context))])
                           tasks))]
    (try
      (->> task-futures
           (map (fn [[task-id f]] [task-id @f]))
           (into {}))
      (catch Throwable t
        ;; Cancel outstanding futures so their sub-workflow finally blocks
        ;; run and release any acquired execution environments.
        (doseq [[_ f] task-futures]
          (when-not (future-done? f)
            (future-cancel f)))
        (throw t)))))

(defn notify-batch-start [batch on-task-start]
  (when on-task-start
    (doseq [[task-id _] batch] (on-task-start task-id))))

(defn notify-batch-complete [results on-task-complete]
  (when on-task-complete
    (doseq [[task-id result] results] (on-task-complete task-id result))))

(defn partition-results [results]
  (let [ok-results (->> results (filter #(dag/ok? (second %))) (map first))
        err-results (->> results (filter #(not (dag/ok? (second %)))) (map first))]
    {:completed ok-results :failed err-results}))

(defn aggregate-results [all-results]
  (let [results (vals all-results)
        artifacts (->> results (mapcat #(get-in % [:data :artifacts] [])))
        pr-infos (->> results (keep #(get-in % [:data :pr-info])) vec)
        worktree-paths (->> results (keep #(get-in % [:data :worktree-path])) vec)
        total-tokens (->> results (map #(get-in % [:data :metrics :tokens] 0)) (reduce + 0))
        total-cost (->> results (map #(get-in % [:data :metrics :cost-usd] 0.0)) (reduce + 0.0))
        total-duration (->> results (map #(get-in % [:data :metrics :duration-ms] 0)) (reduce + 0))]
    (cond-> {:artifacts artifacts :total-tokens total-tokens :total-cost total-cost :total-duration total-duration}
      (seq pr-infos) (assoc :pr-infos pr-infos)
      (seq worktree-paths) (assoc :worktree-paths worktree-paths))))

(defn has-failed-dependency?
  "Check if a task depends on any task in the failed set."
  [task failed-ids]
  (some failed-ids (get task :task/deps #{})))

(defn propagate-failures
  "Mark tasks whose deps include any failed task as transitively failed."
  [tasks-map failed-ids]
  (loop [propagated failed-ids]
    (let [newly-failed (->> tasks-map
                            (remove (fn [[tid _]] (contains? propagated tid)))
                            (filter (fn [[_tid task]] (has-failed-dependency? task propagated)))
                            (map first)
                            set)]
      (if (empty? newly-failed)
        propagated
        (recur (into propagated newly-failed))))))

(defn- make-resume-hint-event
  "Create a :dag/resume-hint event map for the event stream."
  [workflow-id reset-at auto-resume? completed-ids wait-ms]
  {:event/type :dag/resume-hint
   :event/timestamp (str (java.time.Instant/now))
   :workflow/id workflow-id
   :dag/reset-at (str reset-at)
   :dag/auto-resume? auto-resume?
   :dag/completed-task-ids (vec completed-ids)
   :dag/wait-ms wait-ms})

(defn- emit-resume-hint!
  "Emit a :dag/resume-hint event if we know when the rate limit clears."
  [event-stream workflow-id reset-at auto-resume? completed-ids wait-ms]
  (when (and event-stream reset-at)
    (try
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (publish! event-stream
                  (make-resume-hint-event workflow-id reset-at auto-resume?
                                          completed-ids wait-ms)))
      (catch Exception _ nil))))

(defn- checkpoint-log-data
  "Build the log data map for a checkpoint event."
  [reset-at wait-ms completed-count message]
  {:data {:reset-at (str reset-at)
          :wait-minutes (long (/ wait-ms 60000))
          :completed-tasks completed-count
          :message message}})

(defn- log-checkpoint-info
  "Log actionable checkpoint info for the user."
  [logger auto-resume? reset-at wait-ms completed-count]
  (when logger
    (if auto-resume?
      (log/info logger :dag-orchestrator :dag/checkpoint-for-resume
                (checkpoint-log-data reset-at wait-ms completed-count
                                     "Workflow checkpointed. Resume with: miniforge run --resume <workflow-id>"))
      (log/info logger :dag-orchestrator :dag/checkpoint-for-manual-resume
                (checkpoint-log-data reset-at wait-ms completed-count
                                     "Rate limit too far out. Resume manually when ready.")))))

(defn handle-rate-limit-in-batch
  "Handle rate-limited tasks in a batch. Returns either:
   - {:action :continue ...} to re-queue tasks (short wait or backend switch)
   - {:action :pause :result <paused-map>} to checkpoint and stop execution

   For medium waits (30min-2hrs), emits a :dag/paused event with :reset-at
   so external schedulers can auto-resume. For long waits (>2hrs), emits
   the same event but flags it as requiring manual resume."
  [context rate-limited-ids new-completed failed-ids all-results batch-results
   event-stream workflow-id logger]
  (let [decision (resilience/handle-rate-limited-batch
                  context rate-limited-ids new-completed logger batch-results)]
    (if (= :continue (:action decision))
      decision
      (let [{:keys [artifacts]} (aggregate-results all-results)
            reset-at (:reset-at decision)
            auto-resume? (= :checkpoint-and-resume (:action decision))]
        ;; Emit enriched pause event with reset time for scheduler
        (resilience/emit-dag-paused! event-stream workflow-id new-completed
                                     (:reason decision))
        (emit-resume-hint! event-stream workflow-id reset-at auto-resume?
                          new-completed (:wait-ms decision))
        (log-checkpoint-info logger auto-resume? reset-at
                             (:wait-ms decision 0) (count new-completed))
        {:action :pause
         :result (dag-execution-paused new-completed failed-ids
                                       artifacts decision)}))))

(defn emit-completed-checkpoints!
  "Emit task-completed events for checkpointing."
  [completed-task-ids results event-stream workflow-id]
  (doseq [tid completed-task-ids]
    (resilience/emit-dag-task-completed! event-stream workflow-id tid (get results tid))))

(defn emit-batch-events!
  "Emit checkpointing events for successful results in a completed batch.
   Returns nil to keep event emission side-effect only."
  [results event-stream workflow-id]
  (let [completed-task-ids (->> results
                                (filter (fn [[_task-id result]] (dag/ok? result)))
                                (map first))]
    (emit-completed-checkpoints! completed-task-ids results event-stream workflow-id)
    nil))

(defn find-unreached-tasks
  "Identify tasks that are neither completed nor failed — stuck due to unmet deps."
  [tasks-map completed-ids all-failed]
  (->> (keys tasks-map)
       (remove #(or (contains? completed-ids %) (contains? all-failed %)))
       (map (fn [tid]
              {:task-id tid
               :unmet-deps (vec (remove completed-ids
                                        (get-in tasks-map [tid :task/deps] #{})))}))))

(defn log-unreached-tasks! [logger tasks-map completed-ids all-failed]
  (let [unreached (find-unreached-tasks tasks-map completed-ids all-failed)]
    (when (seq unreached)
      (log/info logger :dag-orchestrator :dag/unreached-tasks
                {:data {:unreached-count (count unreached)
                        :stuck-deps unreached}}))))

(defn finalize-dag
  "Build the terminal result when no more tasks are ready."
  [tasks-map completed-ids all-failed all-results sub-workflow-ids iteration logger]
  (log-unreached-tasks! logger tasks-map completed-ids all-failed)
  (let [metrics-agg (aggregate-results all-results)
        unreached (- (count tasks-map) (count completed-ids) (count all-failed))]
    (log/info logger :dag-orchestrator :dag/completed
              {:data {:completed (count completed-ids)
                      :failed (count all-failed)
                      :unreached unreached
                      :iterations iteration}})
    (cond-> (assoc (dag-execution-result (count completed-ids) (count all-failed) (:artifacts metrics-agg) metrics-agg
                                         :unreached unreached)
                   :tasks-unreached unreached
                   :sub-workflow-ids (vec sub-workflow-ids))
      (:pr-infos metrics-agg) (assoc :pr-infos (:pr-infos metrics-agg))
      (:worktree-paths metrics-agg) (assoc :worktree-paths (:worktree-paths metrics-agg)))))

(defn- register-batch-branches!
  "Register every successfully-completed task's persisted branch in the
   per-workflow branch registry. Runs once per batch, AFTER all futures
   in the batch have joined — keeps mutation off the hot path of any
   running task and guarantees siblings in the same batch never observe
   each other's incomplete state."
  [registry-atom batch-results]
  (when registry-atom
    (doseq [[task-id result] batch-results]
      (when (dag/ok? result)
        (when-let [branch (get-in result [:data :task-branch])]
          (swap! registry-atom dag/register-branch task-id {:branch branch}))))))

(defn execute-dag-loop [tasks-map context logger]
  (let [{:keys [on-task-start on-task-complete]} context
        max-parallel (get context :max-parallel 4)
        event-stream (or (:event-stream context)
                         (get-in context [:execution/opts :event-stream]))
        workflow-id (:workflow-id context)
        pre-completed (get context :pre-completed-ids #{})
        ;; Per-workflow branch registry. Lives on context so all sibling
        ;; futures in `execute-tasks-batch` see the same atom; no global
        ;; state. Empty for brand-new runs; pre-populated during resume
        ;; from the prior run's checkpoint events when that lands.
        registry-atom (atom (dag/create-branch-registry))
        context (assoc context :dag/branch-registry registry-atom)]

    (when (seq pre-completed)
      (log/info logger :dag-orchestrator :dag/resuming
                {:data {:pre-completed-count (count pre-completed)
                        :pre-completed-ids (vec pre-completed)}}))

    (loop [completed-ids pre-completed
           failed-ids #{}
           all-results {}
           sub-workflow-ids []
           current-backend (get context :current-backend)
           iteration 0]
      (let [all-failed (propagate-failures tasks-map failed-ids)
            ready-tasks (compute-ready-tasks tasks-map completed-ids all-failed)]
        (cond
          ;; No more work — finalize
          (empty? ready-tasks)
          (finalize-dag tasks-map completed-ids all-failed all-results
                        sub-workflow-ids iteration logger)

          ;; Safety valve
          (> iteration 100)
          (dag-execution-error (count completed-ids) (count all-failed) "Max iterations exceeded")

          ;; Execute next batch
          :else
          (let [batch (select-non-conflicting-batch ready-tasks max-parallel)
                _ (notify-batch-start batch on-task-start)
                ctx (cond-> context
                      current-backend (assoc :current-backend current-backend))
                results (execute-tasks-batch batch execute-single-task ctx)
                _ (notify-batch-complete results on-task-complete)
                {:keys [completed failed]} (partition-results results)
                batch-sub-ids (map (fn [[tid _]] (keyword (str "dag-task-" tid))) batch)
                {:keys [rate-limited-ids other-failed-ids]} (resilience/analyze-batch-for-rate-limits results)
                new-completed (into completed-ids completed)
                new-failed (into failed-ids other-failed-ids)]

            ;; Register persisted branches BEFORE the rate-limit decision so
            ;; that even on pause the registry reflects what's been written
            ;; — checkpoint/resume can replay it from the event stream
            ;; alongside :dag/task-completed.
            (register-batch-branches! registry-atom results)
            (emit-completed-checkpoints! completed results event-stream workflow-id)

            (if (seq rate-limited-ids)
              (let [decision (handle-rate-limit-in-batch
                              context rate-limited-ids new-completed new-failed
                              all-results results event-stream workflow-id logger)]
                (if (= :continue (:action decision))
                  (recur new-completed
                         new-failed
                         (merge all-results (select-keys results completed))
                         (into sub-workflow-ids batch-sub-ids)
                         (:new-backend decision)
                         (inc iteration))
                  (:result decision)))
              (recur new-completed
                     (into failed-ids failed)
                     (merge all-results results)
                     (into sub-workflow-ids batch-sub-ids)
                     current-backend
                     (inc iteration)))))))))

(defn warn-potential-monolith
  "Log a warning when a plan may be under-decomposed — single task but
   multiple components or many files."
  [plan logger]
  (let [tasks (:plan/tasks plan [])
        components (->> tasks (keep :task/component) distinct)
        all-files (->> tasks (mapcat :task/exclusive-files) distinct)]
    (when (and (<= (count tasks) 1)
               (or (> (count components) 1)
                   (> (count all-files) 5)))
      (log/info logger :dag-orchestrator :plan/potential-monolith
                {:data {:task-count (count tasks)
                        :component-count (count components)
                        :file-count (count all-files)}}))))

(defn- task-defs->forest-shape
  "Adapt post-wiring task-defs (`:task/deps` set) to the shape
   `validate-dag-forest` expects (`:task/dependencies` vec).

   In v1 this fed the plan-time gate; in v2 the gate is dropped and
   the result is used only for informational logging (so the operator
   sees `:dag/multi-parent-detected` for fan-in plans, but the
   orchestrator runs them anyway via `merge-parent-branches!`).

   Sorts deps before vectorizing so logged payloads are deterministic
   across runs — sets have no iteration order."
  [task-defs]
  (mapv (fn [t]
          {:task/id (:task/id t)
           ;; sort-by str so heterogeneous task-id types (UUID/keyword/
           ;; string) still produce a total order — `sort` would throw
           ;; on a mixed set.
           :task/dependencies (vec (sort-by str (:task/deps t #{})))})
        task-defs))

(defn- log-multi-parent-detected!
  "Emit an informational log (NOT an anomaly) when the plan has
   multi-parent tasks. v2 runs them via the merge path; the log
   surfaces plan shape on the dashboard so operators can see fan-in
   patterns without the orchestrator rejecting work."
  [logger plan task-defs]
  (when-let [non-forest (dag/validate-dag-forest (task-defs->forest-shape task-defs))]
    (log/info logger :dag-orchestrator :dag/multi-parent-detected
              {:data {:plan-id (:plan/id plan)
                      :multi-parent-tasks (:multi-parent-tasks non-forest)}})))

(defn execute-plan-as-dag [plan context]
  (let [logger (or (:logger context) (log/create-logger {:min-level :info}))
        _ (warn-potential-monolith plan logger)
        task-defs (plan->dag-tasks plan context)
        _ (log-multi-parent-detected! logger plan task-defs)
        tasks-map (->> task-defs (map (fn [t] [(:task/id t) t])) (into {}))
        pre-completed (get context :pre-completed-ids #{})
        ctx (cond-> context
              (seq pre-completed) (assoc :pre-completed-ids pre-completed))]
    (log/info logger :dag-orchestrator :dag/starting
              {:data {:plan-id (:plan/id plan)
                      :task-count (count task-defs)
                      :pre-completed (count pre-completed)}})
    (execute-dag-loop tasks-map ctx logger)))

;--- Layer 3: Workflow Integration

(defn maybe-parallelize-plan [plan context]
  (let [estimate (estimate-parallel-speedup plan)]
    (when (:parallelizable? estimate)
      (let [logger (or (:logger context) (log/create-logger {:min-level :info}))]
        (log/info logger :dag-orchestrator :plan/parallelizing
                  {:data {:plan-id (:plan/id plan)
                          :task-count (:task-count estimate)
                          :max-parallel (:max-parallel estimate)
                          :estimated-speedup (:estimated-speedup estimate)}}))
      (execute-plan-as-dag plan context))))

;--- Rich Comment
(comment
  (def sample-plan
    {:plan/id (random-uuid)
     :plan/name "test-plan"
     :plan/tasks
     (let [a (random-uuid)
           b (random-uuid)
           c (random-uuid)]
       [{:task/id a :task/description "Task A" :task/type :implement :task/dependencies []}
        {:task/id b :task/description "Task B" :task/type :implement :task/dependencies []}
        {:task/id c :task/description "Task C" :task/type :test :task/dependencies [a b]}])})

  (parallelizable-plan? sample-plan)
  (estimate-parallel-speedup sample-plan)
  (parallelizable-plan? {:plan/tasks [{:task/id (random-uuid) :task/description "Only task"}]})
  (execute-plan-as-dag sample-plan {:logger (log/create-logger {:min-level :debug})})

  :leave-this-here)
