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
  "Git and shell plumbing for task worktrees: `run-git` and `run-shell`,
   which the worktree executor namespaces and the scratch-ref GC queue call,
   and the git queries built on them. Tests replace `run-git` here to script
   git."
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]
   [clojure.java.shell :as shell]
   [clojure.string :as str])
  (:import
   [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} run-git
  "Execute a git command and return the result."
  [& args]
  (try
    (apply shell/sh "git" args)
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn ^{:stratum 0} run-shell
  "Execute a shell command and return the result."
  [& args]
  (try
    (apply shell/sh args)
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn ^{:stratum 0} hook-free-git-args
  "`run-git` args for `git -C worktree-path <args>` with hooks and
   fsmonitor off, for every persist call that touches the index or a ref.

   The task worktree shares `.git/config` with the host checkout, so a
   hooks path or fsmonitor program set there, by the operator or by a task,
   would otherwise run. `--no-verify` skips only pre-commit and commit-msg;
   prepare-commit-msg, post-commit, post-checkout and reference-transaction
   still run, and `status`, `add`, `commit` and `checkout` each start the
   fsmonitor program."
  [worktree-path args]
  (let [hook-free-options ["-c" "core.hooksPath=/dev/null"
                           "-c" "core.fsmonitor=false"]]
    (-> ["-C" worktree-path]
        (into hook-free-options)
        (into args))))

(defn- ^{:stratum 0} command-output
  "Return command stdout when it is a string; otherwise return empty text."
  [result]
  (let [output (get result :out)]
    (if (string? output) output "")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} git-version
  "Get git version."
  []
  (let [result (run-git "--version")]
    (if (zero? (:exit result))
      {:available? true
       :git-version (str/trim (:out result))}
      {:available? false
       :reason "git not found"})))

(defn ^{:stratum 1} resolve-branch-sha
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
      (let [sha (str/trim (command-output r))]
        (when (seq sha) sha)))))

(defn ^{:stratum 1} remove-worktree
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

(defn ^{:stratum 1} derive-parent-repo-path
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
      (let [git-common-str (str/trim (command-output r))]
        (if (seq git-common-str)
          (let [;; --git-common-dir may return a relative path; resolve against
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
                      "git rev-parse --git-common-dir returned no usable path"
                      {:worktree-path worktree-path})))
      (result/err :derive-parent-repo-failed
                  (str "git rev-parse --git-common-dir failed: " (:err r))
                  {:worktree-path worktree-path
                   :stderr        (get r :err "")}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} notify-file-written!
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
