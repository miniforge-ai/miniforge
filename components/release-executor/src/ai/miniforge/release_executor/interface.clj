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
(ns ai.miniforge.release-executor.interface
  "Public API for the release-executor component.

   Provides release phase execution for creating PRs from code artifacts.
   This component is extracted to avoid circular dependencies between
   workflow and phase components."
  (:require
   [ai.miniforge.release-executor.core :as core]
   [ai.miniforge.release-executor.git :as git]
   [ai.miniforge.release-executor.metadata :as metadata]
   [ai.miniforge.release-executor.shell :as shell]))

;------------------------------------------------------------------------------ Layer 0

;; Main execution function
(def ^{:stratum 0} execute-release-phase
  "Execute the release phase.

   Arguments:
   - workflow-state - Workflow state with :workflow/artifacts
   - context        - Execution context with :worktree-path, :logger, etc.
   - opts           - Options with :releaser (optional)

   Returns:
   {:success? bool
    :artifacts [release-artifact]
    :errors []
    :metrics {...}}

   Example:
     (execute-release-phase workflow-state
                            {:worktree-path \"/path/to/repo\"
                             :create-pr? false}
                            {:releaser my-releaser-agent})"
  core/execute-release-phase)

(def ^{:stratum 0} release-branch-name
  "The fixed release branch of a run (and DAG task). Args: [run-id task-id
   task-description]. The release uses it whenever its context carries a
   run id."
  metadata/release-branch-name)

(def ^{:stratum 0} prepare-release
  "First half of the release: metadata, branch and commit, nothing pushed.
   Arguments as execute-release-phase. Success carries :prepared, the
   plain-data input publish-release takes."
  core/prepare-release)

(def ^{:stratum 0} publish-release
  "Second half of the release: push the prepared branch and open the PR.
   Args: [prepared context], `prepared` a prepare-release result's
   :prepared. Returns a result as execute-release-phase."
  core/publish-release)

;; Git operations (for testing and advanced use)
(def ^{:stratum 0} check-gh-auth!
  "Check if GitHub CLI is available and authenticated.
   Returns {:available? bool :authenticated? bool :error string}"
  git/check-gh-auth!)

(def ^{:stratum 0} create-branch!
  "Create a new git branch from main.
   Returns {:success? bool :branch string :base-branch string :error string}"
  git/create-branch!)

;; Git child-process environment
(def ^{:stratum 0} repository-env-vars
  "The variables that bind a git command to one repository (what `git
   rev-parse --local-env-vars` prints). Every git call the release and
   fix-loop paths make drops them, so it acts on the worktree it runs in."
  shell/repository-env-vars)

(def ^{:stratum 0} without-repository-env
  "`env` (a map) without `repository-env-vars`. Args: [env]."
  shell/without-repository-env)

(def ^{:stratum 0} local-git-env
  "This process's environment without `repository-env-vars`, for a git
   child process. Args: []."
  shell/local-git-env)

(def ^{:stratum 0} commit-changes!
  "Commit staged changes. Args: [worktree-path message], plus optional
   git-args (global git options placed before `commit`, e.g. to disable
   hooks) and extra-env (e.g. GIT_INDEX_FILE for a temporary index).
   Returns {:success? bool :commit-sha string :error string}"
  git/commit-changes!)

(def ^{:stratum 0} push-branch!
  "Push branch to remote. Args: [worktree-path branch], plus optional
   github-token and git-args (global git options placed before `push`).
   Returns {:success? bool :error string}"
  git/push-branch!)

(def ^{:stratum 0} create-pr!
  "Create a PR using gh CLI.
   Returns {:success? bool :pr-number int :pr-url string :error string}"
  git/create-pr!)
