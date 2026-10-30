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
  "The release phase's publish half: push the branch enter-release
   prepared and open its PR, from leave-release, after the gates ran.

   Split out of `release` (rule 210)."
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase.interface.gate :as phase-gate]
            [ai.miniforge.phase-software-factory.messages :as messages]
            [ai.miniforge.release-executor.interface :as release-executor]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workspace.interface :as workspace]
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

(defn- ^{:stratum 0} publish-prepared
  "Publish what enter-release prepared through `publish-context`, folding
   the outcome into the phase `result`: on success its output gains the
   release artifacts, the publish metrics and :workflow/pr-info. A failure
   keeps the prepared output (readiness, consultation) beside the error. A
   result with no :release/prepared fails rather than completing without a
   push."
  [result publish-context logger]
  (let [failed (fn [failure]
                 (cond-> failure
                   (:output result) (assoc :output (:output result))))]
    (if-let [prepared (get-in result [:output :release/prepared])]
      (try
        (let [exec-result (release-executor/publish-release prepared publish-context)]
          (cond
            ;; PR creation was asked for: a release with no PR is not done.
            (and (:success? exec-result) (:create-pr? publish-context)
                 (not (get-in exec-result [:artifacts 0 :artifact/content :pr-url])))
            (do (log/error logger :release :release/pr-missing {})
                (failed (response/failure (messages/t :release/pr-missing)
                                          {:data {:type :release/pr-missing}})))

            (:success? exec-result)
            (let [content (:artifact/content (first (:artifacts exec-result)))]
              (update result :output
                      (fn [output]
                        (cond-> (assoc output
                                       :release/status    :completed
                                       :release/artifacts (:artifacts exec-result)
                                       :release/metrics   (merge (:release/metrics output)
                                                                 (:metrics exec-result)))
                          (:pr-url content)
                          (assoc :workflow/pr-info {:pr-number  (:pr-number content)
                                                    :pr-url     (:pr-url content)
                                                    :branch     (:branch content)
                                                    :commit-sha (:commit-sha content)})))))

            :else
            (do (log/error logger :release :release/publish-failed
                           {:data {:errors (:errors exec-result)}})
                (failed (response/failure (messages/t :release/publish-failed)
                                          {:data {:errors  (:errors exec-result)
                                                  :metrics (:metrics exec-result)}})))))
        (catch Exception e
          (log/error logger :release :release/publish-threw {:message (ex-message e)})
          (failed (response/failure e))))
      (failed (response/failure (messages/t :release/not-prepared)
                                {:data {:type :release/not-prepared}})))))

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
   ;; Allow disabling PR creation via config or execution opts. An explicit
   ;; false in opts wins (an `or` read it as absent).
   :create-pr? (let [opt (get-in ctx [:execution/opts :create-pr?])]
                 (if (some? opt) opt (get config :create-pr? true)))
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
   ;; :origin is the run's lineage (the parent's for a DAG task), the same
   ;; across resume attempts; the release branch is keyed on it. A DAG
   ;; task restored from a checkpoint written before lineage was recorded
   ;; has only :workflow/parent-id, which keyed its branch then.
   :provenance {:workflow (or (get-in ctx [:execution/input :workflow/parent-id])
                              (get-in ctx [:execution/id]))
                :origin   (or (get-in ctx [:execution/input :workflow/origin-id])
                              (get-in ctx [:execution/input :workflow/parent-id])
                              (get-in ctx [:execution/origin-id])
                              (get-in ctx [:execution/id]))
                :spec     (get-in ctx [:execution/input :spec/path])
                :task     (get-in ctx [:execution/input :task/id])}
   ;; Resolve and inject GitHub token for capsule gh CLI auth
   :github-token (resolve-github-token ctx)})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish-when-allowed
  "Publish (push and PR) what enter-release prepared, when the prepare
   succeeded, did not skip, and no gate failed; otherwise return `ctx`
   unchanged. Called by leave-release before it records the outcome."
  [ctx]
  (let [result   (get-in ctx [:phase :result])
        publish? (and (phase/result-succeeded? result)
                      (not (get-in result [:output :skipped]))
                      (not= :release/zero-files (get-in result [:error :data :type]))
                      (not (phase-gate/gate-failed? (:phase ctx))))]
    (if-not publish?
      ctx
      (let [logger    (or (get-in ctx [:execution/logger])
                          (log/create-logger {:min-level :debug :output :human}))
            config    (phase/merge-with-defaults (get ctx :phase-config))
            published (publish-prepared result (build-publish-context ctx config logger) logger)]
        ;; Single derived projection of the canonical pr-info onto ctx-level
        ;; :workflow/pr-info, which several consumers still read directly
        ;; (runner_events completion event, CLI summary, evidence-bundle) until
        ;; they migrate to response/release-pr-info. The projected value is
        ;; derived from the authority via that accessor ({:result result} is the
        ;; phase-result shape it expects), so this copy can never hold a value
        ;; the authority doesn't — a derived cache of one reader, not a second
        ;; source of truth.
        (cond-> (assoc-in ctx [:phase :result] published)
          (phase/result-succeeded? published)
          (assoc :workflow/pr-info (response/release-pr-info {:result published})))))))
