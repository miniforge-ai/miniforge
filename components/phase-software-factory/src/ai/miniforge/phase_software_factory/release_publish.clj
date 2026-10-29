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
(ns ai.miniforge.phase-software-factory.release-publish
  "The release phase's publish half: the release executor context that
   pushing the branch and opening the PR need.

   Split out of `release` (rule 210)."
  (:require [ai.miniforge.workspace.interface :as workspace]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resolve-github-token
  "Resolve GitHub token for capsule PR operations.
   Mirrors the resolution order from docker.clj's resolve-git-token:
   1. Execution context (passed by task runner)
   2. MINIFORGE_GIT_TOKEN env var (universal override)
   3. GH_TOKEN env var (GitHub-specific)
   4. `gh auth token` CLI fallback (host-side only, pre-capsule)"
  [ctx]
  (or (get-in ctx [:execution/opts :github-token])
      (get-in ctx [:execution/github-token])
      (System/getenv "MINIFORGE_GIT_TOKEN")
      (System/getenv "GH_TOKEN")
      (try (let [r (shell/sh "gh" "auth" "token")]
             (when (zero? (:exit r))
               (str/trim (:out r))))
           (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-publish-context
  "Build the release executor context from phase context: where the
   branch lives, how to reach it, and the PR settings. Includes
   :github-token so the release executor can inject GH_TOKEN into the
   capsule's environment for gh CLI authentication.

   The phase-local `logger` is threaded in so the release executor's
   log calls (phase-started / fail / phase-completed) are emitted
   even when the workflow ctx lacks :execution/logger — observed in
   the 2026-05-03 dogfood, where release failed in 0ms with zero
   visible log lines because the executor's logger was nil."
  [ctx config logger]
  {;; Preserve the original precedence — explicit ctx worktree paths
   ;; win over config; the blessed resolver is the LAST resort,
   ;; adding the opts key + the governed-fail / CWD decision (no
   ;; silent user.dir downgrade) in place of the old bare fallback.
   :worktree-path (or (get-in ctx [:execution/worktree-path])
                      (get-in ctx [:worktree-path])
                      (get-in config [:worktree-path])
                      (workspace/resolve-execution-workdir ctx "release"))
   :executor (get-in ctx [:execution/executor])
   :environment-id (get-in ctx [:execution/environment-id])
   :logger (or (get-in ctx [:execution/logger]) logger)
   :artifact-store (get-in ctx [:execution/artifact-store])
   :event-stream (:event-stream ctx)
   ;; Allow disabling PR creation via config or execution opts
   :create-pr? (or (get-in ctx [:execution/opts :create-pr?])
                   (get config :create-pr? true))
   ;; PR base override for a dependency-chained DAG task: the DAG
   ;; orchestrator passes the parent task's branch so this task's PR
   ;; STACKS on the parent (base = parent branch) instead of opening
   ;; against the default branch with a cumulative diff. Absent (root
   ;; tasks / non-DAG runs) → executor falls back to the default branch.
   :base-branch (get-in ctx [:execution/opts :release/base-branch])
   ;; PR provenance → rendered as YAML frontmatter on the PR body so
   ;; a PR maps deterministically back to its workflow run + spec.
   ;; :workflow is the PARENT run id for a DAG sub-task (shared across
   ;; the run's PRs) else this run's id; :task distinguishes DAG tasks.
   :provenance {:workflow (or (get-in ctx [:execution/input :workflow/parent-id])
                              (get-in ctx [:execution/id]))
                :spec     (get-in ctx [:execution/input :spec/path])
                :task     (get-in ctx [:execution/input :task/id])}
   ;; Resolve and inject GitHub token for capsule gh CLI auth
   :github-token (resolve-github-token ctx)})
