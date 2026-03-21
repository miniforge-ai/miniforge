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
