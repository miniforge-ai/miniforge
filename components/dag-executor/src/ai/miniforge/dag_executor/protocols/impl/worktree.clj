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

(ns ai.miniforge.dag-executor.protocols.impl.worktree
  "Worktree executor implementation (fallback).

   Provides task isolation via git worktrees. This is the fallback executor
   when no container runtime is available. Isolation is limited to filesystem
   separation - processes run on the host."
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [clojure.java.shell :as shell]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.nio.file Files StandardCopyOption CopyOption]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def ^:dynamic *warn-fn*
  "Emit a warning message to stderr.
   Rebind via `binding` in tests to capture warnings without stderr noise."
  (fn [msg] (binding [*out* *err*] (println msg))))

(defn- expand-home
  "Expand a leading `~/` in a path string to the user home directory.
   Pass-through for absolute paths and bare paths that do not start with `~/`."
  [path]
  (let [home (System/getProperty "user.home")]
    (cond
      (str/starts-with? path "~/") (str home (subs path 1))
      (= "~" path)                 home
      :else                        path)))

(defn default-base-path
  "Default location for git worktrees acquired by the worktree executor.

   Arity 0: returns the hardcoded default `~/.miniforge/worktrees`.

   Arity 1 (config map): reads `:workflow/worktree-root` from the supplied
   map, expands any leading `~/`, and falls back to the no-arg default when
   the key is absent or blank. This lets `create-worktree-executor` respect
   the user `default-user-config.edn` setting without requiring a disk read
   at namespace load time.

   Lives under `~/.miniforge/worktrees/` rather than `/tmp` so the worktree
   (and any uncommitted implementer file writes living in it) survives reboots
   and macOS-tmpfs cleanups. The 2026-04-17 gates workflow lost 8+ minutes of
   working code when its `/tmp` worktree was GC'd on process-tree restart;
   persistence outside `/tmp` is the foundation for the broader work in
   `work/worktree-persistence-scratch-branch.spec.edn`.

   Callers that need an ephemeral location (CI runs, sandbox tests) can opt in
   to `/tmp` by setting `:workflow/worktree-root \"/tmp/miniforge-worktrees\"`
   in their user config, or by passing `:base-path` directly to
   `create-worktree-executor`."
  ([]
   (str (System/getProperty "user.home") "/.miniforge/worktrees"))
  ([config]
   (let [configured (get config :workflow/worktree-root)]
     (if (and configured (not (str/blank? (str configured))))
       (expand-home configured)
       (default-base-path)))))

(def default-max-concurrent 4)

(defn default-archive-dir
  "Default location for persisted task work archives.
   Lives under the user home rather than /tmp so checkpoints survive reboots
   and can be referenced from evidence bundles long after the run ends."
  []
  (str (System/getProperty "user.home") "/.miniforge/checkpoints"))

;; ============================================================================
;; Legacy /tmp detection
;; ============================================================================

(def ^:private legacy-tmp-worktrees-path
  "Legacy default worktree base directory that lived under /tmp.
   Created before the 2026-04-17 gates-workflow data-loss incident forced the
   move to `~/.miniforge/worktrees`. Checked at creation time to prompt
   migration without blocking active workflows."
  "/tmp/miniforge-worktrees")

(defn legacy-tmp-worktrees-exist?
  "Returns true if the legacy /tmp/miniforge-worktrees directory exists and
   contains at least one entry (indicating active or stale worktrees).

   Public so tests can rebind via `with-redefs` without touching the filesystem."
  []
  (let [dir (File. ^String legacy-tmp-worktrees-path)]
    (and (.exists dir)
         (.isDirectory dir)
         (let [files (.listFiles dir)]
           (and (some? files) (pos? (alength files)))))))

;; Non-private so tests can `(reset! worktree/legacy-tmp-warned? false)` before
;; exercising the warning path. `compare-and-set!` in check-legacy-tmp-worktrees!
;; ensures the advisory fires at most once per JVM session even under concurrent
;; load — without this, all four default worktree slots could race to emit
;; identical WARNING lines before any of them acquires worktree-lock.
(defonce legacy-tmp-warned?
  (atom false))

(defn check-legacy-tmp-worktrees!
  "Warn once per JVM session when the legacy /tmp/miniforge-worktrees directory
   has entries.

   Uses `compare-and-set!` on `legacy-tmp-warned?` so exactly one thread emits
   the advisory, even when multiple worktree-create calls race at startup.

   Does NOT error — graceful coexistence per the worktree-persistence spec
   constraint. Existing /tmp worktrees remain usable; the warning tells
   operators how to migrate to the persistent default path.

   Rebind `*warn-fn*` via `binding` in tests to capture the output.
   Reset `legacy-tmp-warned?` to false before tests that assert the warning fires."
  []
  (when (and (legacy-tmp-worktrees-exist?)
             (compare-and-set! legacy-tmp-warned? false true))
    (*warn-fn*
     (str
      "[miniforge] WARNING: legacy worktrees detected at "
      legacy-tmp-worktrees-path "\n"
      "  These worktrees live under /tmp and will not survive a reboot.\n"
      "  To migrate to the persistent default:\n"
      "    1. Add `:workflow/worktree-root \"~/.miniforge/worktrees\"` to\n"
      "       ~/.miniforge/config.edn (this is already the shipped default).\n"
      "    2. Let in-flight tasks complete, then remove the stale /tmp directory:\n"
      "       rm -rf " legacy-tmp-worktrees-path "\n"
      "  New worktrees are now created under: " (default-base-path)))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn run-git
  "Execute a git command and return the result."
  [& args]
  (try
    (apply shell/sh "git" args)
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn run-shell
  "Execute a shell command and return the result."
  [& args]
  (try
    (apply shell/sh args)
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn ensure-directory
  "Ensure a directory exists."
  [path]
  (.mkdirs (File. ^String path)))

;; ============================================================================
;; Git Operations
;; ============================================================================

(defn git-version
  "Get git version."
  []
  (let [result (run-git "--version")]
    (if (zero? (:exit result))
      {:available? true
       :git-version (str/trim (:out result))}
      {:available? false
       :reason "git not found"})))

(def ^:private worktree-lock
  "JVM-level lock for git worktree add commands.
   Git uses a config.lock file internally — concurrent worktree adds from
   multiple threads hit this lock and fail silently. Serializing creation at
   the JVM level prevents the conflict. Tasks still run in parallel once their
   worktrees are acquired.

   `check-legacy-tmp-worktrees!` runs inside this lock so that at most one
   thread emits the advisory warning at a time, avoiding duplicated log lines
   in high-parallelism scenarios where all default concurrent slots start
   simultaneously."
  (Object.))

(defn- resolve-branch-sha
  "Resolve a branch name to its commit sha so `git worktree add -b new path
   <sha>` works even when <branch> is checked out in another worktree.

   The straightforward `git worktree add -b new path <branch>` fails with
   `fatal: '<branch>' is already checked out at <other-worktree>` when the
   parent worktree is the one running miniforge — which is the common case
   for `bb dogfood`. Passing the resolved sha sidesteps the refusal.

   Returns the sha string on success, nil on failure or empty output (caller
   falls back to the branch name and accepts any failure that follows).
   Guards the empty-string case explicitly: `(or \"\" branch)` treats the
   empty string as truthy in Clojure and would pass an empty base-ref to
   `git worktree add`, producing a confusing git error."
  [repo-path branch]
  (let [r (run-git "-C" repo-path "rev-parse" branch)]
    (when (zero? (:exit r))
      (let [sha (str/trim (or (:out r) ""))]
        (when (seq sha) sha)))))

(defn create-worktree
  "Create a git worktree for the task.

   Serializes creation through worktree-lock to prevent concurrent git
   config.lock conflicts when multiple sub-workflows acquire environments
   simultaneously. The legacy /tmp worktree check also runs inside the lock so
   at most one thread emits the advisory warning at a time.

   Attempts in order:
   1. git worktree add -b <name> <path> <sha-or-ref>
   2. Clean up stale branch/directory and retry step 1
   3. git worktree add --detach <path> — detached HEAD (last resort)

   Detached HEAD is a real fallback path here, but downstream `archive-bundle!`
   handles it defensively by creating a real branch at HEAD before bundling.
   This one-two punch keeps `git worktree add` failures from cascading into
   broken bundles whose only ref is a bare HEAD."
  [base-path repo-path worktree-name branch]
  (locking worktree-lock
    (check-legacy-tmp-worktrees!)
    (let [worktree-path (str base-path "/" worktree-name)
          base-sha      (resolve-branch-sha repo-path branch)
          base-ref      (or base-sha branch)]
      (ensure-directory base-path)
      (let [result (run-git "-C" repo-path
                            "worktree" "add" "-b" worktree-name
                            worktree-path base-ref)]
        (if (zero? (:exit result))
          (result/ok {:worktree-path worktree-path
                      :base-sha base-sha
                      :base-ref branch})
          ;; Failed — clean up stale branch/directory from a prior run and retry
          (do
            (run-shell "rm" "-rf" worktree-path)
            (run-git "-C" repo-path "worktree" "prune")
            (run-git "-C" repo-path "branch" "-D" worktree-name)
            (let [result2 (run-git "-C" repo-path
                                   "worktree" "add" "-b" worktree-name
                                   worktree-path base-ref)]
              (if (zero? (:exit result2))
                (result/ok {:worktree-path worktree-path
                            :base-sha base-sha
                            :base-ref branch})
                ;; Still failing — detached HEAD as last resort
                (let [result3 (run-git "-C" repo-path
                                       "worktree" "add" "--detach"
                                       worktree-path)]
                  (if (zero? (:exit result3))
                    (result/ok {:worktree-path worktree-path
                                :base-sha base-sha
                                :base-ref branch})
                    (result/err :worktree-create-failed (:err result3))))))))))))

(defn remove-worktree
  "Remove a git worktree."
  [worktree-path]
  ;; Try git worktree remove first
  (let [result (run-git "-C" worktree-path
                        "worktree" "remove" "--force" worktree-path)]
    (if (zero? (:exit result))
      (result/ok {:released? true})
      ;; Fallback: just delete the directory
      (do
        (run-shell "rm" "-rf" worktree-path)
        (result/ok {:released? true})))))

;; ============================================================================
;; Archive-based persistence (local-tier fidelity for :governed parity)
;; ============================================================================

(def ^:private default-commit-message
  "Commit message for the per-phase persist commit when the caller did not
   override `:message`. Internal infrastructure; not user-visible at
   commit time."
  "phase checkpoint")

(def ^:private default-base-ref
  "Final fallback base ref when neither :base-branch nor :base-ref was
   passed by the caller. Used only by direct callers of `archive-bundle!`;
   the runner threads the real base from environment metadata."
  "main")

(defn- ensure-archive-dir
  "Create the archive directory if it does not exist. Returns the absolute path."
  [archive-dir]
  (let [d (File. ^String archive-dir)]
    (.mkdirs d)
    (.getAbsolutePath d)))

;; Each git helper below does exactly one operation, returns a result/ok
;; on exit 0 with the parsed output, or a result/err carrying the git
;; stderr. Persist treats all errors as diagnosable failures rather than
;; silent zeros — Copilot review on PR #729 caught the original "treat
;; non-zero exit as no-changes" pattern as a silent-failure vector.

(def ^:private detached-head-sentinel
  "What `git rev-parse --abbrev-ref HEAD` prints in a detached worktree.
   Treated as 'no branch' for archive purposes — `archive-bundle!` creates
   a real branch before recording the bundle so the result always has a
   `refs/heads/<name>` ref, never a bare HEAD."
  "HEAD")

(defn- detached?
  [branch-name]
  (= detached-head-sentinel branch-name))

(defn- current-branch
  "Returns the worktree HEAD branch name, or the literal sentinel
   `\"HEAD\"` when the worktree is detached. Callers that need a real
   branch name should call `ensure-named-branch!` to materialize one."
  [worktree-path]
  (let [r (run-git "-C" worktree-path "rev-parse" "--abbrev-ref" "HEAD")]
    (if (zero? (:exit r))
      (result/ok (str/trim (get r :out "")))
      (result/err :archive-no-branch (get r :err "could not resolve HEAD")))))

(def ^:private detached-branch-prefix
  "Prefix prepended to a `task-id` to derive the recovery branch name in
   `ensure-named-branch!`. Skipped when the task-id already starts with
   the prefix (the common path: persist-workspace! defaults task-id to
   the environment-id, which is itself shaped like `task-<id>`)."
  "task-")

(defn- recovery-branch-name
  "Branch name to materialize when recovering from a detached scratch.
   Avoids a `task-task-` double-prefix when task-id already carries it."
  [task-id]
  (let [s (str task-id)]
    (if (str/starts-with? s detached-branch-prefix)
      s
      (str detached-branch-prefix s))))

(defn- ensure-named-branch!
  "If the worktree is in detached-HEAD state, create a real branch at HEAD
   so the bundle records `refs/heads/<name>` (not just bare HEAD).

   Returns result/ok with the branch name to use for bundling. Pass-through
   when the worktree is already on a named branch.

   Uses `git checkout -B` (force-reset to HEAD) so recovery is idempotent
   across reruns with the same task-id and across leftover local refs from
   prior runs. The branch is task-namespaced internal infrastructure, not
   a user-curated ref; resetting it is the right semantics."
  [worktree-path branch task-id]
  (if-not (detached? branch)
    (result/ok branch)
    (let [name (recovery-branch-name task-id)
          r    (run-git "-C" worktree-path "checkout" "-B" name)]
      (if (zero? (:exit r))
        (result/ok name)
        (result/err :archive-detach-recovery-failed
                    (or (:err r)
                        "could not create or reset branch from detached HEAD"))))))

(defn- dirty?
  [worktree-path]
  (let [r (run-git "-C" worktree-path "status" "--porcelain")]
    (if (zero? (:exit r))
      (result/ok (-> (get r :out "") str/trim seq boolean))
      (result/err :archive-status-failed
                  (get r :err "git status --porcelain failed")))))

(defn- commits-ahead
  "Count commits on HEAD not reachable from base-ref."
  [worktree-path base-ref]
  (let [r (run-git "-C" worktree-path "rev-list" "--count"
                   (str base-ref "..HEAD"))]
    (if (zero? (:exit r))
      (try
        (result/ok (Integer/parseInt (str/trim (get r :out "0"))))
        (catch NumberFormatException _
          (result/err :archive-rev-list-parse-failed
                      (str "could not parse rev-list output: " (:out r)))))
      (result/err :archive-rev-list-failed
                  (get r :err "git rev-list --count failed")))))

(defn- stage-all!
  [worktree-path]
  (let [r (run-git "-C" worktree-path "add" "-A")]
    (if (zero? (:exit r))
      (result/ok :staged)
      (result/err :archive-stage-failed (get r :err "git add -A failed")))))

(defn- commit-staged!
  "Commit staged changes with --no-verify. Persist runs inside a scratch
   worktree where the host pre-commit hook (bb pre-commit) fails because
   deps are not installed. Persist is infrastructure preservation, not a
   validated commit — the gate layer upstream owns validation."
  [worktree-path message]
  (let [r (run-git "-C" worktree-path "commit"
                   "--no-verify" "--allow-empty-message" "-m" message)]
    (if (zero? (:exit r))
      (result/ok :committed)
      (result/err :archive-commit-failed
                  (get r :err "git commit --no-verify failed")))))

(defn- head-sha
  [worktree-path]
  (let [r (run-git "-C" worktree-path "rev-parse" "HEAD")]
    (if (zero? (:exit r))
      (result/ok (str/trim (get r :out "")))
      (result/err :archive-rev-parse-failed
                  (get r :err "git rev-parse HEAD failed")))))

(defn- write-bundle!
  "Run `git bundle create FILE BRANCH --not BASE`. The `BRANCH --not BASE`
   form records `refs/heads/BRANCH` in the bundle, which is what
   `git fetch <bundle> BRANCH:BRANCH` consumes when the harvest CLI pulls
   the work back into the host repo."
  [worktree-path bundle-path branch base-ref]
  (let [r (run-git "-C" worktree-path "bundle" "create"
                   bundle-path branch "--not" base-ref)]
    (if (zero? (:exit r))
      (result/ok bundle-path)
      (result/err :archive-bundle-failed
                  (get r :err "git bundle create failed")))))

(defn- maybe-commit
  "Commit only when the worktree has dirty changes. `git add -A` already
   ran — if no paths were staged (e.g. all changes were gitignored),
   commit-staged! would fail with `nothing to commit`, which is not an
   archive error."
  [worktree-path message]
  (-> (dirty? worktree-path)
      (result/and-then
       (fn [is-dirty]
         (if is-dirty
           (commit-staged! worktree-path message)
           (result/ok :clean))))))

(defn- bundle-result
  "Final step of the persist pipeline once we know the worktree is ahead
   of base-ref. Reads HEAD, writes the bundle, and packages the persist
   result map."
  [worktree-path branch base-ref archive-dir task-id ahead]
  (let [bundle-path (str (ensure-archive-dir archive-dir) "/" task-id ".bundle")]
    (-> (head-sha worktree-path)
        (result/and-then
         (fn [sha]
           (-> (write-bundle! worktree-path bundle-path branch base-ref)
               (result/map-ok
                (fn [_]
                  {:persisted?    true
                   :bundle-path   bundle-path
                   :commit-sha    sha
                   :branch        branch
                   :commits-ahead ahead}))))))))

(defn- commit-and-bundle!
  "Pipeline: stage -> commit-if-dirty -> recompute ahead -> bundle-or-noop."
  [worktree-path branch base-ref archive-dir task-id message]
  (-> (stage-all! worktree-path)
      (result/and-then (fn [_] (maybe-commit worktree-path message)))
      (result/and-then (fn [_] (commits-ahead worktree-path base-ref)))
      (result/and-then
       (fn [ahead]
         (if (zero? ahead)
           (result/ok {:persisted? false :no-changes? true :branch branch})
           (bundle-result worktree-path branch base-ref archive-dir task-id ahead))))))

(defn- already-clean?
  "True when the worktree has no dirty paths AND no commits ahead of
   base-ref — there is nothing to bundle. Returns a result so callers see
   git failures rather than treating them as a clean state."
  [worktree-path base-ref]
  (-> (dirty? worktree-path)
      (result/and-then
       (fn [is-dirty]
         (if is-dirty
           (result/ok false)
           (-> (commits-ahead worktree-path base-ref)
               (result/map-ok zero?)))))))

(defn archive-bundle!
  "Persist a worktree task work as a git bundle.

   Pipeline: resolve current branch -> check for dirty changes or commits
   ahead of base-ref -> if clean, return no-changes; otherwise stage,
   commit on the task branch with --no-verify, and write a bundle of
   `base-ref..HEAD` to <archive-dir>/<task-id>.bundle.

   Arguments:
   - worktree-path: absolute path to the scratch worktree
   - opts: {:archive-dir string  -- destination dir for bundles
           :task-id      string  -- identifier used as the bundle filename
           :base-ref     string  -- base branch the worktree was forked from
           :message      string  -- commit message for the persist commit}

   Returns result/ok with one of:
   - {:persisted? true  :bundle-path str :commit-sha str :branch str}
   - {:persisted? false :no-changes? true :branch str}
   Returns result/err on any git failure (no silent fallbacks)."
  [worktree-path {:keys [archive-dir task-id base-ref message]
                  :or   {message  default-commit-message
                         base-ref default-base-ref}}]
  (-> (current-branch worktree-path)
      (result/and-then
       (fn [raw-branch]
         (-> (ensure-named-branch! worktree-path raw-branch task-id)
             (result/and-then
              (fn [branch]
                (-> (already-clean? worktree-path base-ref)
                    (result/and-then
                     (fn [clean?]
                       (if clean?
                         (result/ok {:persisted? false :no-changes? true :branch branch})
                         (commit-and-bundle! worktree-path branch base-ref
                                             archive-dir task-id message))))))))))))

(defn restore-from-bundle!
  "Restore work from a previously-persisted bundle into the host repo.

   `git fetch <bundle> <branch>:<branch>` pulls the branch and all reachable
   commits into the host repo without touching the working tree.

   Arguments:
   - host-repo-path: path to a worktree of the host repo
   - opts: {:bundle-path str :branch str}

   Returns result/ok with {:restored? true :branch str} on success."
  [host-repo-path {:keys [bundle-path branch]}]
  (letfn [(failure [fallback command]
            (result/err :archive-restore-failed
                        (or (not-empty (:err command)) fallback)))
          (checked-out-branch []
            (let [restore-ref (str "refs/miniforge/resume/" branch)
                  cleanup     (atom nil)
                  outcome
                  (try
                    (let [fetch-ref (run-git "-C" host-repo-path "fetch"
                                             bundle-path (str branch ":" restore-ref))]
                      (if-not (zero? (:exit fetch-ref))
                        (failure "git fetch from bundle failed" fetch-ref)
                        (let [branch-sha  (run-git "-C" host-repo-path "rev-parse" branch)
                              restore-sha (run-git "-C" host-repo-path "rev-parse" restore-ref)
                              ancestor    (run-git "-C" host-repo-path "merge-base" "--is-ancestor"
                                                   (str/trim (:out restore-sha))
                                                   (str/trim (:out branch-sha)))]
                          (cond
                            (not (zero? (:exit branch-sha)))
                            (failure "git rev-parse branch failed" branch-sha)

                            (not (zero? (:exit restore-sha)))
                            (failure "git rev-parse resume ref failed" restore-sha)

                            (zero? (:exit ancestor))
                            (result/ok {:restored? true
                                        :branch branch
                                        :bundle-path bundle-path
                                        :already-checked-out? true})

                            (= 1 (:exit ancestor))
                            (result/err :archive-restore-failed
                                        "Checked-out branch does not contain bundled checkpoint commit")

                            :else
                            (failure "git merge-base failed" ancestor)))))
                    (finally
                      (reset! cleanup
                              (run-git "-C" host-repo-path "update-ref" "-d" restore-ref))))]
              (if (and (result/ok? outcome)
                       (not (zero? (:exit @cleanup))))
                (failure "git cleanup of resume ref failed" @cleanup)
                outcome)))]
    (try
      (let [r (run-git "-C" host-repo-path "fetch"
                       bundle-path (str branch ":" branch))]
        (if (zero? (:exit r))
          (result/ok {:restored? true :branch branch :bundle-path bundle-path})
          (if (str/includes? (get r :err "")
                             "refusing to fetch into branch")
            (checked-out-branch)
            (failure "git fetch from bundle failed" r))))
      (catch Exception e
        (result/err :archive-restore-failed (.getMessage e))))))

;; ============================================================================
;; Worktree Lifecycle Registry
;; ============================================================================

(def ^:private worktree-lifecycle-registry
  "Lightweight atom-based registry tracking worktrees created by the executor.

   Structure: {workflow-id {:scratch-ref   str
                             :worktree-path str
                             :created-at    long  ; epoch ms
                             :status        :active | :released
                             :released-at   long  ; epoch ms, only present when :released}}

   This is an in-memory atom that retains all entries (including :released)
   for the lifetime of the process. It is NOT persisted and is NOT pruned by
   `gc-scratch-refs!`. Git-ref cleanup happens independently via
   `gc-scratch-refs!`, which neither reads nor mutates this registry. This
   lets post-mortem tooling inspect which workflows wrote which scratch
   commits even after the worktrees are gone."
  (atom {}))

(defn get-worktree-registry
  "Return a snapshot of the current worktree lifecycle registry.

   Each entry is keyed by workflow-id and contains:
     :scratch-ref    String ref name under refs/miniforge/scratch/<workflow-id>
     :worktree-path  Absolute path to the scratch worktree
     :created-at     Epoch ms when the worktree was acquired
     :status         :active (worktree present) or :released (worktree removed)
     :released-at    Epoch ms of release (only present when :status :released)"
  []
  @worktree-lifecycle-registry)

(defn register-worktree-entry!
  "Register a new worktree entry in the lifecycle registry.

   Called on worktree creation to record the scratch-ref and worktree path
   associated with a workflow. If an entry already exists for workflow-id,
   it is replaced — creation is idempotent across re-acquisitions."
  [workflow-id worktree-path]
  (swap! worktree-lifecycle-registry
         assoc workflow-id
         {:scratch-ref   (scratch-commit/scratch-ref-name workflow-id)
          :worktree-path worktree-path
          :created-at    (System/currentTimeMillis)
          :status        :active})
  nil)

(defn release-worktree-entry!
  "Mark a registry entry as :released while preserving the scratch ref.

   Called on worktree destruction. The entry is retained in the registry
   (with :status :released and :released-at epoch ms) so post-mortem tooling
   and GC can inspect it. The underlying git scratch ref is NOT deleted here —
   use gc-scratch-refs! for that."
  [workflow-id]
  (swap! worktree-lifecycle-registry
         (fn [registry]
           (if (contains? registry workflow-id)
             (update registry workflow-id assoc
                     :status      :released
                     :released-at (System/currentTimeMillis))
             registry)))
  nil)

(defn- find-workflow-id-by-worktree
  "Find the workflow-id whose registry entry has the given worktree-path.
   Returns nil when no match is found."
  [worktree-path]
  (some (fn [[wf-id entry]]
          (when (= worktree-path (:worktree-path entry))
            wf-id))
        @worktree-lifecycle-registry))

;; ============================================================================
;; Parent-Repo Derivation
;; ============================================================================

(defn derive-parent-repo-path
  "Derive the parent repository path from a linked worktree path.

   Uses `git rev-parse --git-common-dir` which returns the shared .git
   directory of the parent (main) repository for linked worktrees. The
   parent repo path is the directory that contains that .git directory.

   For a plain (non-worktree) repo `--git-common-dir` returns `.git` —
   the parent in that case is the repo root itself, which is correct.

   Returns result/ok {:parent-repo-path string} or result/err."
  [worktree-path]
  (let [r (run-git "-C" worktree-path "rev-parse" "--git-common-dir")]
    (if (zero? (:exit r))
      (let [git-common-str  (str/trim (or (:out r) ""))
            ;; --git-common-dir may return a relative path; resolve against
            ;; the worktree's CWD so we get the correct absolute path even
            ;; when the worktree lives far from the parent repo.
            git-common-file (let [f (File. ^String git-common-str)]
                              (if (.isAbsolute f)
                                f
                                (File. ^String worktree-path ^String git-common-str)))
            abs-git-common  (.getAbsolutePath git-common-file)
            parent-repo     (.getAbsolutePath
                             (.getParentFile (File. ^String abs-git-common)))]
        (result/ok {:parent-repo-path parent-repo}))
      (result/err :derive-parent-repo-failed
                  (str "git rev-parse --git-common-dir failed: " (:err r))
                  {:worktree-path worktree-path
                   :stderr        (get r :err "")}))))

;; ============================================================================
;; File-Write Scratch-Commit Hook
;; ============================================================================

(defn notify-file-written!
  "Snapshot a file written inside a worktree into the workflow's scratch ref.

   Called by the layer that processes Write tool responses from agents running
   inside the worktree. Derives the parent repo path via git plumbing and
   delegates to scratch-commit! without touching the worktree's index or
   working tree.

   Arguments:
   - worktree-path  Absolute path to the scratch worktree
   - workflow-id    Workflow identifier (used as scratch-ref suffix)
   - phase          Current phase label (e.g. \"implement\", \"verify\")
   - file-path      Absolute path to the file that was written

   Returns result/ok with scratch-commit data or result/err."
  [worktree-path workflow-id phase file-path]
  (-> (derive-parent-repo-path worktree-path)
      (result/and-then
       (fn [{:keys [parent-repo-path]}]
         (scratch-commit/scratch-commit! parent-repo-path workflow-id phase file-path)))))

;; ============================================================================
;; Command Execution
;; ============================================================================

(defn execute-command
  "Execute a command in a directory using ProcessBuilder.

   This allows proper environment variable handling and working directory."
  [workdir command env-map]
  (let [cmd-str (if (string? command)
                  command
                  (str/join " " command))
        start-time (System/currentTimeMillis)]
    (try
      (let [pb (ProcessBuilder. ["sh" "-c" cmd-str])
            _ (.directory pb (File. ^String workdir))
            _ (when (seq env-map)
                (let [pb-env (.environment pb)]
                  (doseq [[k v] env-map]
                    (.put pb-env (name k) (str v)))))
            process (.start pb)
            stdout (slurp (.getInputStream process))
            stderr (slurp (.getErrorStream process))
            exit-code (.waitFor process)]
        (result/ok {:exit-code exit-code
                    :stdout stdout
                    :stderr stderr
                    :duration-ms (- (System/currentTimeMillis) start-time)}))
      (catch Exception e
        (result/err :exec-failed (.getMessage e))))))

;; ============================================================================
;; File Operations
;; ============================================================================

(defn- path-inside-worktree?
  "True when `file` (a canonicalized File) is the worktree root or is nested
   inside it. Prevents path-traversal escape via `../` segments."
  [^File worktree ^File file]
  (let [root-path (str (.getPath worktree) File/separator)
        file-path (.getPath file)]
    (or (= file-path (.getPath worktree))
        (str/starts-with? file-path root-path))))

(defn- safe-worktree-path
  "Resolve `relative-path` inside `worktree-path` and return the canonical
   File, or nil if the resolved path would escape the worktree sandbox or
   if canonicalization fails (I/O error, invalid path)."
  [worktree-path relative-path]
  (try
    (let [worktree (.getCanonicalFile (File. ^String worktree-path))
          file     (.getCanonicalFile (File. worktree ^String relative-path))]
      (when (path-inside-worktree? worktree file)
        file))
    (catch java.io.IOException _ nil)))

(defn copy-file-to
  "Copy a file into the worktree."
  [worktree-path local-path remote-path]
  (let [dest (safe-worktree-path worktree-path remote-path)]
    (if-not dest
      (result/err :path-traversal (str "path escapes worktree sandbox: " remote-path))
      (try
        (let [source (File. ^String local-path)]
          (.mkdirs (.getParentFile dest))
          (Files/copy (.toPath source)
                      (.toPath dest)
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (result/ok {:copied-bytes (.length source)}))
        (catch Exception e
          (result/err :copy-failed (.getMessage e)))))))

(defn copy-file-from
  "Copy a file from the worktree."
  [worktree-path remote-path local-path]
  (let [source (safe-worktree-path worktree-path remote-path)]
    (if-not source
      (result/err :path-traversal (str "path escapes worktree sandbox: " remote-path))
      (try
        (let [dest (File. ^String local-path)]
          (.mkdirs (.getParentFile dest))
          (Files/copy (.toPath source)
                      (.toPath dest)
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (result/ok {:copied-bytes (.length source)}))
        (catch Exception e
          (result/err :copy-failed (.getMessage e)))))))

;; ============================================================================
;; Public Utility Functions
;; ============================================================================

(defn create-worktree!
  "Create a git worktree at <base-path>/task-<task-id> from <branch>.

   Takes a map with keys:
   - :base-path  - Base directory under which the worktree directory is created
   - :repo-path  - Path to the git repository (used as -C arg)
   - :branch     - Branch/ref to check out in the worktree
   - :task-id    - Identifier for the task; the directory will be task-<task-id>

   Returns {:worktree-path string :branch string :task-id string} on success,
   or a result/err map on failure."
  [{:keys [base-path repo-path branch task-id]}]
  (let [worktree-name (str "task-" task-id)
        result (create-worktree base-path repo-path worktree-name branch)]
    (if (result/ok? result)
      (result/ok {:worktree-path (:worktree-path (:data result))
                  :branch branch
                  :task-id (str task-id)})
      result)))

(defn remove-worktree!
  "Remove a git worktree created by create-worktree!.

   Takes a map with keys:
   - :worktree-path - Absolute path to the worktree directory to remove"
  [{:keys [worktree-path]}]
  (remove-worktree worktree-path))

;; ============================================================================
;; WorktreeExecutor Record
;; ============================================================================

(defrecord WorktreeExecutor [config base-path max-concurrent]
  proto/TaskExecutor

  (executor-type [_this] :worktree)

  (available? [_this]
    (try
      (result/ok (git-version))
      (catch Exception e
        (result/ok {:available? false :reason (.getMessage e)}))))

  (acquire-environment! [_this task-id env-config]
    (let [worktree-name (str "task-" (subs (str task-id) 0 8))
          repo-path     (get env-config :repo-path ".")
          branch        (get env-config :branch "main")
          workflow-id   (get env-config :workflow-id)
          create-result (create-worktree base-path repo-path worktree-name branch)]
      (if (result/ok? create-result)
        (let [data          (:data create-result)
              worktree-path (:worktree-path data)]
          ;; Lifecycle hook: register the scratch-ref for this workflow so
          ;; callers can call notify-file-written! and find the right path.
          (when workflow-id
            (register-worktree-entry! workflow-id worktree-path))
          (result/ok (proto/create-environment-record
                      worktree-name :worktree task-id worktree-path
                      (assoc env-config
                             :metadata {:worktree-path worktree-path
                                        :repo-path     repo-path
                                        :base-sha      (:base-sha data)
                                        ;; Stored so persist-workspace! can bundle
                                        ;; the right base..HEAD range without
                                        ;; guessing the parent branch.
                                        :base-branch   branch}))))
        create-result)))

  (execute! [_this environment-id command opts]
    (let [worktree-path (str base-path "/" environment-id)
          workdir (get opts :workdir worktree-path)
          env-map (merge {} (:env opts))]
      (execute-command workdir command env-map)))

  (copy-to! [_this environment-id local-path remote-path]
    (let [worktree-path (str base-path "/" environment-id)]
      (copy-file-to worktree-path local-path remote-path)))

  (copy-from! [_this environment-id remote-path local-path]
    (let [worktree-path (str base-path "/" environment-id)]
      (copy-file-from worktree-path remote-path local-path)))

  (release-environment! [_this environment-id]
    (let [worktree-path (str base-path "/" environment-id)]
      ;; Lifecycle hook: mark the registry entry as :released while preserving
      ;; the scratch ref. The scratch ref survives until gc-scratch-refs! runs.
      (when-let [wf-id (find-workflow-id-by-worktree worktree-path)]
        (release-worktree-entry! wf-id))
      (try
        (remove-worktree worktree-path)
        (catch Exception e
          (result/err :release-failed (.getMessage e))))))

  (environment-status [_this environment-id]
    (let [worktree-path (str base-path "/" environment-id)]
      (if (.exists (File. ^String worktree-path))
        (result/ok {:status :running})
        (result/ok {:status :stopped}))))

  (persist-workspace! [_this environment-id opts]
    ;; Local-tier persistence parity with :governed (per N11 §7.4 fidelity goal):
    ;; bundle the task branch into a durable archive so work survives
    ;; release-environment!. Without this, the host worktree had no record of
    ;; per-task work — successful agent output was destroyed when the scratch
    ;; worktree was torn down.
    ;;
    ;; `:base-ref` only falls back across :base-branch -> :base-ref ->
    ;; default-base-ref. The task branch (`:branch` opt) is intentionally
    ;; NOT a fallback: using it would make `commits-ahead` compute
    ;; `task..HEAD` (always 0) and silently skip bundling.
    (let [worktree-path (str base-path "/" environment-id)
          configured-archive-dir (or (get opts :archive-dir)
                                     (get-in config [:archive-dir])
                                     (default-archive-dir))
          ;; Workflow id (when present) namespaces the archive path so
          ;; concurrent runs do not collide on the same task-id stem.
          archive-dir (if-let [wf (get opts :workflow-id)]
                        (str configured-archive-dir "/" wf)
                        configured-archive-dir)
          base-ref    (or (get opts :base-branch)
                          (get opts :base-ref)
                          default-base-ref)
          task-id     (or (get opts :task-id) environment-id)
          message     (or (get opts :message) default-commit-message)]
      (archive-bundle! worktree-path
                       {:archive-dir archive-dir
                        :task-id     task-id
                        :base-ref    base-ref
                        :message     message})))

  (restore-workspace! [_this _environment-id opts]
    ;; Pull a previously-persisted bundle back into the host repo object DB.
    ;; Caller supplies :host-repo-path (any worktree of the host repo works)
    ;; and :bundle-path. The branch shows up as a ref in the host repo and
    ;; can be checked out wherever the orchestrator wants.
    (let [host-repo (or (get opts :host-repo-path)
                        (get opts :workdir)
                        ".")]
      (restore-from-bundle! host-repo opts))))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-worktree-executor
  "Create a worktree-based executor (fallback).

   Config:
   - :base-path              - Explicit base directory for worktrees. Takes
                               priority over all other path resolution.
   - :workflow/worktree-root - User-config key. The shipped
                               `default-user-config.edn` sets this to
                               `\"~/.miniforge/worktrees\"`. Leading `~/`
                               is expanded to the user home directory.
                               Falls back to the hardcoded default when
                               absent or blank.
   - :max-concurrent         - Max concurrent worktrees (default: 4)

   The previous `/tmp/miniforge-worktrees` default was changed after the
   2026-04-17 gates-workflow lost 8+ minutes of working code to a macOS
   tmpfs cleanup. Ephemeral `/tmp`-rooted runs (CI, sandbox tests) opt in
   by setting `:workflow/worktree-root \"/tmp/miniforge-worktrees\"` in user
   config, or `:base-path` directly in the executor config."
  [config]
  (map->WorktreeExecutor
   {:config         config
    :base-path      (or (get config :base-path)
                        (default-base-path config))
    :max-concurrent (get config :max-concurrent default-max-concurrent)}))
