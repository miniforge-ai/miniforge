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
(ns ai.miniforge.dag-executor.protocols.impl.worktree.archive-steps
  "The single git steps of the archive pipeline (status, stage, commit,
   bundle, detached-HEAD recovery). Each returns result/ok with the parsed
   output on exit 0 and result/err with git's stderr otherwise, never a
   silent zero that would read as no changes."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.string :as str])
  (:import
   [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} ensure-archive-dir
  "Create the archive directory if it does not exist. Returns the absolute path."
  [archive-dir]
  (let [d (File. ^String archive-dir)]
    (.mkdirs d)
    (.getAbsolutePath d)))

(def ^{:stratum 0} ^:private detached-head-sentinel
  "What `git rev-parse --abbrev-ref HEAD` prints in a detached worktree.
   Treated as 'no branch' for archive purposes — `archive-bundle!` creates
   a real branch before recording the bundle so the result always has a
   `refs/heads/<name>` ref, never a bare HEAD."
  "HEAD")

(def ^{:stratum 0} ^:private detached-branch-prefix
  "Prefix prepended to a `task-id` to derive the recovery branch name in
   `ensure-named-branch!`. Skipped when the task-id already starts with
   the prefix (the common path: persist-workspace! defaults task-id to
   the environment-id, which is itself shaped like `task-<id>`)."
  "task-")

(defn ^{:stratum 0} current-branch
  "Returns the worktree HEAD branch name, or the literal sentinel
   `\"HEAD\"` when the worktree is detached. Callers that need a real
   branch name should call `ensure-named-branch!` to materialize one."
  [worktree-path]
  (let [r (worktree/run-git "-C" worktree-path "rev-parse" "--abbrev-ref" "HEAD")]
    (if (zero? (:exit r))
      (result/ok (str/trim (get r :out "")))
      (result/err :archive-no-branch (get r :err "could not resolve HEAD")))))

(defn- ^{:stratum 0} dirty?
  [worktree-path]
  (let [r (worktree/run-git "-C" worktree-path "status" "--porcelain")]
    (if (zero? (:exit r))
      (result/ok (-> (get r :out "") str/trim seq boolean))
      (result/err :archive-status-failed
                  (get r :err "git status --porcelain failed")))))

(defn ^{:stratum 0} commits-ahead
  "Count commits on HEAD not reachable from base-ref."
  [worktree-path base-ref]
  (let [r (worktree/run-git "-C" worktree-path "rev-list" "--count"
                   (str base-ref "..HEAD"))]
    (if (zero? (:exit r))
      (try
        (result/ok (Integer/parseInt (str/trim (get r :out "0"))))
        (catch NumberFormatException _
          (result/err :archive-rev-list-parse-failed
                      (str "could not parse rev-list output: " (:out r)))))
      (result/err :archive-rev-list-failed
                  (get r :err "git rev-list --count failed")))))

(defn ^{:stratum 0} stage-all!
  [worktree-path]
  (let [r (worktree/run-git "-C" worktree-path "add" "-A")]
    (if (zero? (:exit r))
      (result/ok :staged)
      (result/err :archive-stage-failed (get r :err "git add -A failed")))))

(defn- ^{:stratum 0} commit-staged!
  "Commit staged changes with --no-verify and without a signature.
   Persist runs inside a scratch worktree where the host pre-commit hook
   (bb pre-commit) fails because deps are not installed, and where the
   operator's global commit.gpgsign would route a machine commit through
   their signing agent -- which, when locked or absent, fails the commit
   (\"1Password: failed to fill whole buffer\") and silently strands the
   task's work in the worktree (trap-bench series 4-6). Persist is
   infrastructure preservation, not a validated or attributable commit;
   the gate layer upstream owns validation."
  [worktree-path message]
  (let [r (worktree/run-git "-C" worktree-path "-c" "commit.gpgsign=false" "commit"
                   "--no-verify" "--allow-empty-message" "-m" message)]
    (if (zero? (:exit r))
      (result/ok :committed)
      (result/err :archive-commit-failed
                  (get r :err "git commit --no-verify failed")))))

(defn- ^{:stratum 0} head-sha
  [worktree-path]
  (let [r (worktree/run-git "-C" worktree-path "rev-parse" "HEAD")]
    (if (zero? (:exit r))
      (result/ok (str/trim (get r :out "")))
      (result/err :archive-rev-parse-failed
                  (get r :err "git rev-parse HEAD failed")))))

(defn- ^{:stratum 0} write-bundle!
  "Run `git bundle create FILE BRANCH --not BASE`. The `BRANCH --not BASE`
   form records `refs/heads/BRANCH` in the bundle, which is what
   `git fetch <bundle> BRANCH:BRANCH` consumes when the harvest CLI pulls
   the work back into the host repo."
  [worktree-path bundle-path branch base-ref]
  (let [r (worktree/run-git "-C" worktree-path "bundle" "create"
                   bundle-path branch "--not" base-ref)]
    (if (zero? (:exit r))
      (result/ok bundle-path)
      (result/err :archive-bundle-failed
                  (get r :err "git bundle create failed")))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} detached?
  [branch-name]
  (= detached-head-sentinel branch-name))

(defn- ^{:stratum 1} recovery-branch-name
  "Branch name to materialize when recovering from a detached scratch.
   Avoids a `task-task-` double-prefix when task-id already carries it."
  [task-id]
  (let [s (str task-id)]
    (if (str/starts-with? s detached-branch-prefix)
      s
      (str detached-branch-prefix s))))

(defn ^{:stratum 1} maybe-commit
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

(defn ^{:stratum 1} bundle-result
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

(defn ^{:stratum 1} already-clean?
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} ensure-named-branch!
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
          r    (worktree/run-git "-C" worktree-path "checkout" "-B" name)]
      (if (zero? (:exit r))
        (result/ok name)
        (result/err :archive-detach-recovery-failed
                    (or (:err r)
                        "could not create or reset branch from detached HEAD"))))))
