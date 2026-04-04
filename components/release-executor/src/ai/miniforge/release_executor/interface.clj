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
   [ai.miniforge.release-executor.git :as git]))

;------------------------------------------------------------------------------ Layer 0
;; Main execution function

(def execute-release-phase
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

;------------------------------------------------------------------------------ Layer 1
;; Git operations (for testing and advanced use)

(def check-gh-auth!
  "Check if GitHub CLI is available and authenticated.
   Returns {:available? bool :authenticated? bool :error string}"
  git/check-gh-auth!)

(def create-branch!
  "Create a new git branch from main.
   Returns {:success? bool :branch string :base-branch string :error string}"
  git/create-branch!)

(def commit-changes!
  "Commit staged changes.
   Returns {:success? bool :commit-sha string :error string}"
  git/commit-changes!)

(def push-branch!
  "Push branch to remote.
   Returns {:success? bool :error string}"
  git/push-branch!)

(def create-pr!
  "Create a PR using gh CLI.
   Returns {:success? bool :pr-number int :pr-url string :error string}"
  git/create-pr!)
