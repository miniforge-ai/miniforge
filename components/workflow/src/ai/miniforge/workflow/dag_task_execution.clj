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
(ns ai.miniforge.workflow.dag-task-execution
  "Runs one DAG task's sub-workflow to completion and translates the
   result, split out of `dag-orchestrator` (rule 210 — a 1810-line
   namespace with a non-monotonic layer stack, miniforge#1317).
   Sibling `dag-sub-workflow` builds the config/input/opts this
   consumes; kept separate so run-mini-workflow's own call chain
   doesn't stack underneath construction's chain in one file."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.dag-merge-anomaly :as merge-anomaly]
   [ai.miniforge.workflow.dag-plan :as dag-plan]
   [ai.miniforge.workflow.dag-sub-workflow :as dag-sub-workflow]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} extract-sub-workflow-error
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

(defn ^{:stratum 0} extract-pr-info-from-result
  "Extract PR info from a sub-workflow result if the release phase produced one."
  [result task-def]
  (let [release-result (get-in result [:execution/phase-results :release])
        pr-info (response/release-pr-info release-result)]
    (when pr-info
      (merge pr-info
             {:task-id (:task/id task-def)
              :deps (set (map dag-plan/normalize-task-id (:task/dependencies task-def [])))}))))

(defn- ^{:stratum 0} task-result-branch
  "Branch name produced by the sub-workflow's persist step for this task.

   In governed mode the runner sets `:execution/task-branch` directly. In
   local mode the worktree executor's `environment-id` IS the branch name
   created by `git worktree add -b <env-id>`. Either is fine; we pick
   whichever is present, falling back to nil so callers know nothing
   persisted."
  [result]
  (or (:execution/task-branch result)
      (:execution/environment-id result)))

(defn ^{:stratum 0} workflow-result->dag-result [task-id description wf-result]
  (if (:success? wf-result)
    (dag/ok (cond-> {:task-id task-id
                     :description description
                     :status :implemented
                     :artifacts [(:artifact wf-result)]
                     :metrics (:metrics wf-result)}
              (:pr-info wf-result)    (assoc :pr-info (:pr-info wf-result))
              (:task-branch wf-result) (assoc :task-branch (:task-branch wf-result))))
    (let [error (:error wf-result)
          ;; `run-mini-workflow` short-circuits a v2 multi-parent merge
          ;; failure by putting the FULL typed anomaly map (not a string)
          ;; here — dag/err's message must be a string or unwrap-anomaly
          ;; silently falls back to a generic message. Extract the
          ;; anomaly's own message for that slot and keep the raw map
          ;; reachable under :merge/anomaly for :anomaly/category-based
          ;; classification downstream (dashboard, evidence bundle).
          anomaly (when (merge-anomaly/merge-error? error) error)]
      (dag/err :task-execution-failed
               (if anomaly (:anomaly/message anomaly) error)
               (cond-> {:task-id task-id :metrics (:metrics wf-result)}
                 anomaly (assoc :merge/anomaly anomaly))))))

(defn ^{:stratum 0} placeholder-result [task-id description]
  (dag/ok {:task-id task-id
           :description description
           :status :implemented
           :artifacts []
           :metrics dag-plan/zero-metrics}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-mini-workflow
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
  (let [sub-workflow (dag-sub-workflow/task-sub-workflow task-def context)
        sub-input    (dag-sub-workflow/task-sub-input task-def context)
        sub-opts     (dag-sub-workflow/task-sub-opts context task-def)
        merge-anomaly (:dag/merge-anomaly sub-opts)]
    (if merge-anomaly
      ;; Multi-parent merge failed before we even entered the sub-workflow.
      ;; Surface the FULL typed anomaly map (not just its message) so
      ;; downstream consumers — workflow-result->dag-result, the dashboard,
      ;; the evidence bundle — can classify the failure by
      ;; `:anomaly/category` and access the raw `:merge/conflicts`,
      ;; `:merge/parents`, `:git/stderr` etc. Stage 2 will use this
      ;; structured shape as the resolution sub-workflow's input.
      (dag-plan/workflow-failure merge-anomaly nil)
      (let [run-pipeline (:execution/run-pipeline-fn context)
            result       (run-pipeline sub-workflow sub-input sub-opts)
            artifacts    (:execution/artifacts result)
            metrics      (:execution/metrics result)
            pr-info      (extract-pr-info-from-result result task-def)
            task-branch  (task-result-branch result)]
        ;; `:execution/artifacts` accumulates across phases AND across repair
        ;; iterations (each `update :execution/artifacts into ...` in state.clj
        ;; / execution.clj appends). When a task succeeds after one or more
        ;; review:changes-requested cycles, the vector ends up holding the
        ;; failing-iteration verifier output AHEAD of the succeeding one's.
        ;; Picking `(first artifacts)` would make the task-completed event
        ;; report the EARLIEST iteration as the artifact-of-record — that's
        ;; what the 2026-05-12 dogfood showed for PR #861 (verifier said
        ;; "core change is absent" while the merged diff was correct).
        ;; `(last artifacts)` picks the artifact from the final iteration
        ;; that actually shipped the work. See
        ;; project_dogfood_findings_2026_05_12.md → "Stale verifier feedback".
        (if (phase/succeeded? result)
          (cond-> (dag-plan/workflow-success (last artifacts) metrics)
            pr-info (assoc :pr-info pr-info)
            ;; Carry sub-workflow's worktree path so apply-dag-success can
            ;; merge changes back into the parent worktree for release.
            (:execution/worktree-path result)
            (assoc :worktree-path (:execution/worktree-path result))
            ;; Carry the persisted branch name so the orchestrator can register
            ;; it for downstream tasks' base resolution.
            task-branch (assoc :task-branch task-branch))
          (dag-plan/workflow-failure (extract-sub-workflow-error result) metrics))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-single-task [task-def context]
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
                 (str "Task cancelled: " (or (ex-message ie) (.getName (class ie))))
                 {:task-id task-id}))
      (catch Exception e
        (dag/err :task-execution-failed
                 (str "Task failed: " (or (ex-message e) (.getName (class e))))
                 {:task-id task-id})))))
