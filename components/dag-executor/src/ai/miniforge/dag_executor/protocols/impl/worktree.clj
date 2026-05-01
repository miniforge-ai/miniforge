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
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [clojure.java.shell :as shell]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.nio.file Files StandardCopyOption CopyOption]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-base-path "/tmp/miniforge-worktrees")
(def default-max-concurrent 4)

(defn default-archive-dir
  "Default location for persisted task work archives.
   Lives under the user's home rather than /tmp so checkpoints survive reboots
   and can be referenced from evidence bundles long after the run ends."
  []
  (str (System/getProperty "user.home") "/.miniforge/checkpoints"))

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
   worktrees are acquired."
  (Object.))

(defn create-worktree
  "Create a git worktree for the task.

   Serializes creation through worktree-lock to prevent concurrent git
   config.lock conflicts when multiple sub-workflows acquire environments
   simultaneously.

   Attempts in order:
   1. git worktree add -b <name> <path> <branch>  — new branch from branch tip
   2. Clean up stale branch/directory and retry step 1
   3. git worktree add --detach <path>             — detached HEAD (last resort)"
  [base-path repo-path worktree-name branch]
  (locking worktree-lock
    (let [worktree-path (str base-path "/" worktree-name)]
      (ensure-directory base-path)
      (let [result (run-git "-C" repo-path
                            "worktree" "add" "-b" worktree-name
                            worktree-path branch)]
        (if (zero? (:exit result))
          (result/ok {:worktree-path worktree-path})
          ;; Failed — clean up stale branch/directory from a prior run and retry
          (do
            (run-shell "rm" "-rf" worktree-path)
            (run-git "-C" repo-path "worktree" "prune")
            (run-git "-C" repo-path "branch" "-D" worktree-name)
            (let [result2 (run-git "-C" repo-path
                                   "worktree" "add" "-b" worktree-name
                                   worktree-path branch)]
              (if (zero? (:exit result2))
                (result/ok {:worktree-path worktree-path})
                ;; Still failing — detached HEAD as last resort
                (let [result3 (run-git "-C" repo-path
                                       "worktree" "add" "--detach"
                                       worktree-path)]
                  (if (zero? (:exit result3))
                    (result/ok {:worktree-path worktree-path})
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
  "Commit message for the per-phase persist commit when the caller didn't
   override `:message`. Internal infrastructure; not user-visible at
   commit time."
  "phase checkpoint")

(def ^:private default-base-ref
  "Final fallback base ref when neither :base-branch nor :base-ref was
   passed by the caller. Used only by direct callers of `archive-bundle!`;
   the runner threads the real base from environment metadata."
  "main")

(defn- ensure-archive-dir
  "Create the archive directory if it doesn't exist. Returns the absolute path."
  [archive-dir]
  (let [d (File. ^String archive-dir)]
    (.mkdirs d)
    (.getAbsolutePath d)))

;; Each git helper below does exactly one operation, returns a result/ok
;; on exit 0 with the parsed output, or a result/err carrying the git
;; stderr. Persist treats all errors as diagnosable failures rather than
;; silent zeros — Copilot review on PR #729 caught the original "treat
;; non-zero exit as no-changes" pattern as a silent-failure vector.

(defn- current-branch
  [worktree-path]
  (let [r (run-git "-C" worktree-path "rev-parse" "--abbrev-ref" "HEAD")]
    (if (zero? (:exit r))
      (result/ok (str/trim (or (:out r) "")))
      (result/err :archive-no-branch (or (:err r) "could not resolve HEAD")))))

(defn- dirty?
  [worktree-path]
  (let [r (run-git "-C" worktree-path "status" "--porcelain")]
    (if (zero? (:exit r))
      (result/ok (-> (or (:out r) "") str/trim seq boolean))
      (result/err :archive-status-failed
                  (or (:err r) "git status --porcelain failed")))))

(defn- commits-ahead
  "Count commits on HEAD not reachable from base-ref."
  [worktree-path base-ref]
  (let [r (run-git "-C" worktree-path "rev-list" "--count"
                   (str base-ref "..HEAD"))]
    (if (zero? (:exit r))
      (try
        (result/ok (Integer/parseInt (str/trim (or (:out r) "0"))))
        (catch NumberFormatException _
          (result/err :archive-rev-list-parse-failed
                      (str "could not parse rev-list output: " (:out r)))))
      (result/err :archive-rev-list-failed
                  (or (:err r) "git rev-list --count failed")))))

(defn- stage-all!
  [worktree-path]
  (let [r (run-git "-C" worktree-path "add" "-A")]
    (if (zero? (:exit r))
      (result/ok :staged)
      (result/err :archive-stage-failed (or (:err r) "git add -A failed")))))

(defn- commit-staged!
  "Commit staged changes with --no-verify. Persist runs inside a scratch
   worktree where the host's pre-commit hook (bb pre-commit) fails because
   deps aren't installed. Persist is infrastructure preservation, not a
   validated commit — the gate layer upstream owns validation."
  [worktree-path message]
  (let [r (run-git "-C" worktree-path "commit"
                   "--no-verify" "--allow-empty-message" "-m" message)]
    (if (zero? (:exit r))
      (result/ok :committed)
      (result/err :archive-commit-failed
                  (or (:err r) "git commit --no-verify failed")))))

(defn- head-sha
  [worktree-path]
  (let [r (run-git "-C" worktree-path "rev-parse" "HEAD")]
    (if (zero? (:exit r))
      (result/ok (str/trim (or (:out r) "")))
      (result/err :archive-rev-parse-failed
                  (or (:err r) "git rev-parse HEAD failed")))))

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
                  (or (:err r) "git bundle create failed")))))

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
  "Pipeline: stage → commit-if-dirty → recompute ahead → bundle-or-noop."
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
   base-ref — there's nothing to bundle. Returns a result so callers see
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
  "Persist a worktree's task work as a git bundle.

   Pipeline: resolve current branch → check for dirty changes or commits
   ahead of base-ref → if clean, return no-changes; otherwise stage,
   commit on the task branch with --no-verify, and write a bundle of
   `base-ref..HEAD` to <archive-dir>/<task-id>.bundle. A later
   `git fetch <bundle> <branch>:<branch>` round-trips the work into the
   host repo without touching the live worktree.

   Arguments:
   - worktree-path: absolute path to the scratch worktree
   - opts: {:archive-dir string  — destination dir for bundles
           :task-id      string  — identifier used as the bundle filename
           :base-ref     string  — base branch the worktree was forked from
                                   (NEVER the task branch — that would make
                                   commits-ahead always 0)
           :message      string  — commit message for the persist commit}

   Returns result/ok with one of:
   - {:persisted? true  :bundle-path str :commit-sha str :branch str}
   - {:persisted? false :no-changes? true :branch str}
   Returns result/err on any git failure (no silent fallbacks)."
  [worktree-path {:keys [archive-dir task-id base-ref message]
                  :or   {message  default-commit-message
                         base-ref default-base-ref}}]
  (-> (current-branch worktree-path)
      (result/and-then
       (fn [branch]
         (-> (already-clean? worktree-path base-ref)
             (result/and-then
              (fn [clean?]
                (if clean?
                  (result/ok {:persisted? false :no-changes? true :branch branch})
                  (commit-and-bundle! worktree-path branch base-ref
                                      archive-dir task-id message)))))))))

(defn restore-from-bundle!
  "Restore work from a previously-persisted bundle into the host repo.

   `git fetch <bundle> <branch>:<branch>` pulls the branch and all reachable
   commits into the host repo without touching the working tree. The host
   user (or orchestrator) can then check the branch out wherever they want.

   Arguments:
   - host-repo-path: path to a worktree of the host repo (any worktree on
                     the same repo works; fetch goes to the shared object DB)
   - opts: {:bundle-path str :branch str}

   Returns result/ok with {:restored? true :branch str} on success."
  [host-repo-path {:keys [bundle-path branch]}]
  (try
    (let [r (run-git "-C" host-repo-path "fetch"
                     bundle-path (str branch ":" branch))]
      (if (zero? (:exit r))
        (result/ok {:restored? true :branch branch :bundle-path bundle-path})
        (result/err :archive-restore-failed
                    (or (:err r) "git fetch from bundle failed"))))
    (catch Exception e
      (result/err :archive-restore-failed (.getMessage e)))))

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

(defn copy-file-to
  "Copy a file into the worktree."
  [worktree-path local-path remote-path]
  (try
    (let [source (File. ^String local-path)
          dest-path (str worktree-path "/" remote-path)
          dest (File. ^String dest-path)]
      (.mkdirs (.getParentFile dest))
      (Files/copy (.toPath source)
                  (.toPath dest)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (result/ok {:copied-bytes (.length source)}))
    (catch Exception e
      (result/err :copy-failed (.getMessage e)))))

(defn copy-file-from
  "Copy a file from the worktree."
  [worktree-path remote-path local-path]
  (try
    (let [source-path (str worktree-path "/" remote-path)
          source (File. ^String source-path)
          dest (File. ^String local-path)]
      (.mkdirs (.getParentFile dest))
      (Files/copy (.toPath source)
                  (.toPath dest)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (result/ok {:copied-bytes (.length source)}))
    (catch Exception e
      (result/err :copy-failed (.getMessage e)))))

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
          repo-path (get env-config :repo-path ".")
          branch (get env-config :branch "main")
          create-result (create-worktree base-path repo-path worktree-name branch)]
      (if (result/ok? create-result)
        (let [worktree-path (:worktree-path (:data create-result))]
          (result/ok (proto/create-environment-record
                      worktree-name :worktree task-id worktree-path
                      (assoc env-config
                             :metadata {:worktree-path worktree-path
                                        :repo-path repo-path
                                        ;; Stored so persist-workspace! can bundle
                                        ;; the right base..HEAD range without
                                        ;; guessing the parent branch.
                                        :base-branch branch}))))
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
    ;; `:base-ref` only falls back across :base-branch → :base-ref →
    ;; default-base-ref. The task branch (`:branch` opt) is intentionally
    ;; NOT a fallback: using it would make `commits-ahead` compute
    ;; `task..HEAD` (always 0) and silently skip bundling.
    (let [worktree-path (str base-path "/" environment-id)
          configured-archive-dir (or (get opts :archive-dir)
                                     (get-in config [:archive-dir])
                                     (default-archive-dir))
          ;; Workflow id (when present) namespaces the archive path so
          ;; concurrent runs don't collide on the same task-id stem.
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
    ;; Pull a previously-persisted bundle back into the host repo's object DB.
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
   - :base-path - Base directory for worktrees (default: /tmp/miniforge-worktrees)
   - :max-concurrent - Max concurrent worktrees (default: 4)"
  [config]
  (map->WorktreeExecutor
   {:config config
    :base-path (get config :base-path default-base-path)
    :max-concurrent (get config :max-concurrent default-max-concurrent)}))
