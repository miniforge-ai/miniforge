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
(ns ai.miniforge.cli.workflow-runner
  (:require
   [cheshire.core :as json]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.workflow.interface.resume :as workflow-resume]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.chain :as chain]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.cli.workflow-runner.lifecycle :as lifecycle]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.cli.workflow-runner.paths :as paths]
   [ai.miniforge.cli.workflow-runner.preflight :as preflight]
   [ai.miniforge.cli.workflow-runner.listing :as listing]
   [ai.miniforge.cli.workflow-runner.provenance :as provenance]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.cli.workflow-runner.spec-kanban :as spec-kanban]
   [ai.miniforge.cli.workflow-runner.sandbox :as sandbox]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.phase.interface :as phase]
   [slingshot.slingshot :refer [try+ throw+]]
   [ai.miniforge.dag-executor.interface :as gc-queue]
   [ai.miniforge.cli.worktree :as worktree]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]))

;------------------------------------------------------------------------------ Layer 0

;; Meta-loop context — process-scoped, accumulates metrics across workflows
(defn- ^{:stratum 0} trigger-meta-loop-after-workflow!
  "Record workflow outcome and run a background meta-loop cycle.
   Failures are swallowed — the meta-loop must never crash the workflow runner."
  [workflow-id status failure-class]
  (future
    (try
      (let [ctx (control/meta-loop-context!)]
        (agent/record-workflow-outcome! ctx workflow-id status failure-class)
        (agent/run-cycle-from-context! ctx))
      (catch Exception _e nil))))

;; Workflow interface resolution and pipeline helpers
(defn- ^{:stratum 0} enqueue-workflow-gc-best-effort!
  "Append `workflow-id` to the scratch-ref GC queue.
   Never throws — GC housekeeping must not interfere with the workflow result."
  [workflow-id]
  (gc-hooks/enqueue-workflow-gc-best-effort! gc-queue/enqueue-workflow-gc! workflow-id))

(defn- ^{:stratum 0} run-gc-pass-best-effort!
  "Run the deferred scratch-ref GC pass piggybacked on workflow start.
   Never throws."
  []
  (gc-hooks/run-gc-pass-best-effort! worktree/worktree-root gc-queue/run-deferred-gc!))

(defn- ^{:stratum 0} assert-runtime-alignment!
  [spec context]
  (paths/assert-valid-source-root! context)
  (paths/assert-execution-worktree! context)
  (paths/assert-source-dir-alignment! spec context))

;; Relocated public vars re-exported so existing `:require [... :as
;; workflow-runner]` call sites (cli main, commands) resolve unchanged —
;; same convention as the dag-orchestrator split (miniforge#1485).
(def ^{:stratum 0} run-chain! chain/run-chain!)

(def ^{:stratum 0} list-chains! listing/list-chains!)

(def ^{:stratum 0} list-workflows! listing/list-workflows!)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-workflow! [workflow-id {:keys [version output quiet event-stream dashboard-url]
                                    :or {version "latest" output :pretty quiet false}
                                    :as opts}]
  ;; Piggyback deferred GC on each workflow start — deletes scratch refs
  ;; from finished workflows that are older than the 7-day retention window.
  (run-gc-pass-best-effort!)
  (try
    (let [{:keys [load-workflow run-pipeline]} (setup/resolve-workflow-interface)
          ;; Create event stream if not provided (dashboard-url takes precedence)
          stream (or event-stream
                 (when-not dashboard-url
                   (try
                     (es/create-event-stream)
                     (catch Exception _ nil))))]
      (display/print-workflow-header workflow-id version quiet)
      (let [workflow-input (context/resolve-input opts)
            workflow (setup/load-and-validate-workflow load-workflow workflow-id version)
            artifact-store (execution/create-artifact-store quiet)
            callbacks (setup/create-phase-callbacks quiet)
            ;; Pass dashboard-url in callbacks if provided
            callbacks-with-url (cond-> callbacks
                                 dashboard-url (assoc :dashboard-url dashboard-url))
            progress-cleanup (display/start-progress! stream quiet)
            ;; BD-2b sub-3a: per-workflow manifest. Stamps an :active /
            ;; :live manifest before the pipeline starts and keeps the
            ;; owner lease renewed via a heartbeat while alive. The
            ;; happy path below marks :completed/:failed after drain;
            ;; the finally falls back to :cancelled if neither fired.
            manifest-handle (lifecycle/start-workflow-manifest! workflow-id stream)]
        (try
          (let [result (execution/execute-workflow-pipeline run-pipeline workflow workflow-input callbacks-with-url artifact-store stream)]
            (execution/close-artifact-store artifact-store)
            (lifecycle/mark-manifest-terminal!
             manifest-handle
             (if (phase/succeeded? result) :completed :failed))
            ;; BD-2a shutdown ordering: fence late publishers for this
            ;; workflow, then drain sinks before returning. quiesce!
            ;; rejects any post-terminal `publish!` for this workflow
            ;; (heartbeat / cleanup background threads); drain! waits
            ;; for in-flight publishes to settle and asks each sink to
            ;; flush. Without this, headless exits could land before the
            ;; producer-side completion event was durable.
            (let [shutdown (lifecycle/event-stream-shutdown! stream workflow-id opts)]
              ;; BD-2b sub-3b: archive happens after drain so any
              ;; events that landed between mark-terminal and drain
              ;; are inside live/{wid}/ before the rename. Best-effort
              ;; — failures are logged but don't propagate; the
              ;; boot-time recovery pass picks up half-finished
              ;; archives on next start.
              (lifecycle/archive-workflow-manifest! manifest-handle workflow-id)
              (display/print-result result opts)
              (cond-> result
                (some? shutdown) (assoc :event-durability shutdown))))
          (finally
            ;; If we got here without marking, the pipeline threw or was
            ;; otherwise aborted before the success branch ran. Classify
            ;; as :cancelled to mirror the existing event-stream
            ;; publish-failure-event! :cancelled branch.
            (lifecycle/mark-manifest-terminal! manifest-handle :cancelled)
            (lifecycle/finish-workflow-manifest! manifest-handle)
            (progress-cleanup)
            ;; Schedule deferred GC for this workflow's scratch ref — fires
            ;; here (finally) so it runs on both normal completion and any
            ;; exception path.  The ref will be deleted on a future
            ;; run-gc-pass-best-effort! call once older than 7 days.
            (enqueue-workflow-gc-best-effort! workflow-id)))))
    (catch Exception e
      (when-not quiet
        (println (display/colorize :red (messages/t :workflow-runner/run-error {:error (ex-message e)}))))
      (when (= output :json)
        (println (json/generate-string
                  {:status "error"
                   :error (ex-message e)
                   :data (ex-data e)}
                  {:pretty true})))
      (throw e))))

(defn ^{:stratum 1} run-workflow-from-spec! [spec {:keys [quiet] :or {quiet false} :as opts}]
  ;; Piggyback deferred GC on each spec-driven workflow start.
  (run-gc-pass-best-effort!)
  (try+
    (let [{:keys [load-workflow run-pipeline]} (setup/resolve-workflow-interface)
          ;; Create initial LLM client for workflow selection
          backend-override (:backend opts)
          selection-llm-client (context/create-llm-client nil spec quiet backend-override)
          workflow-type (setup/select-workflow-type spec selection-llm-client quiet)
          workflow-version (get spec :spec/workflow-version "latest")
          workflow (setup/load-or-create-workflow load-workflow workflow-type workflow-version)
          enriched-spec (context/decorate-spec-with-runtime-context spec opts)
          ;; Infer repo URL and branch for execution environment (Docker clone / worktree).
          ;; Reuses sandbox helpers which fall back to `git remote get-url origin`.
          repo-url (sandbox/infer-repo-url spec enriched-spec)
          ;; In governed mode, always clone main — the capsule creates a fresh
          ;; working copy, not a checkout of the host worktree's branch.
          branch   (if (= :governed (:execution-mode opts))
                     (or (:spec/branch spec) "main")
                     (sandbox/infer-branch spec enriched-spec))
          workflow-input (context/spec->workflow-input enriched-spec)
          artifact-store (execution/create-artifact-store quiet)
          event-stream (es/create-event-stream)
          _supervisor (supervisory/attach! event-stream)
          ;; N15-6: see meta-loop attach above for rationale.
          _correlator (correlator/attach! event-stream)
          workflow-id (setup/governed-workflow-id
                       (get-in enriched-spec [:spec/metadata :session-id]) quiet)
          ;; Control state the governed operator channel flips for
          ;; :pause / :resume / :cancel interventions.
          control-state (es/create-control-state)
          ;; Create workflow-specific LLM client for execution
          llm-client (context/create-llm-client workflow spec quiet backend-override)
          callbacks (setup/create-phase-callbacks quiet)
          base-context (let [ctx (context/create-workflow-context
                             {:callbacks callbacks
                              :artifact-store artifact-store
                              :event-stream event-stream
                              :workflow-id workflow-id
                              :workflow-type workflow-type
                              :workflow-version workflow-version
                              :llm-client llm-client
                              :quiet quiet
                              :spec-title (:spec/title spec)
                              :control-state control-state
                              :skip-lifecycle-events true
                              :execution-opts (:execution-opts opts)
                              :source-dir (:spec/source-dir spec)})]
                        ;; Assoc repo-url, branch, and (optionally) execution-mode
                        ;; so runner.clj can clone into Docker or create a worktree.
                        (cond-> (assoc ctx
                                       :repo-url repo-url
                                       :branch branch
                                       :execution-mode (get opts :execution-mode :local))
))
          sandbox? (or (:sandbox opts) (:spec/sandbox spec))
          [context sandbox-cleanup] (sandbox/setup-sandbox-context base-context sandbox? spec enriched-spec quiet)
          progress-cleanup (display/start-progress! event-stream quiet)]
      (try
        ;; Acquire the governed control path first inside the try, after
        ;; every binding that may throw, so the finally below always runs
        ;; its cleanups even if registration fails. The consumer is
        ;; process-scoped; this workflow only registers its live handles.
        (control/register-workflow-control! workflow-id control-state event-stream)
        (when-not quiet
          (display/print-workflow-header (keyword (str "adhoc-" (hash spec))) "adhoc" quiet))
        (dashboard/print-dashboard-status! quiet)
        (assert-runtime-alignment! spec context)
        (provenance/print-runtime-provenance! quiet context)
        (preflight/run-backend-preflight! quiet llm-client context)
        (let [provenance (spec-kanban/move-spec-to-in-progress! (:spec/provenance enriched-spec))
              result (execution/execute-with-events {:run-pipeline run-pipeline
                                           :workflow workflow
                                           :workflow-input workflow-input
                                           :context context
                                           :artifact-store artifact-store
                                           :event-stream event-stream
                                           :workflow-id workflow-id
                                           :sandbox-cleanup sandbox-cleanup
                                           :opts opts})
              outcome-status (if (phase/succeeded? result) :completed :failed)]
          (spec-kanban/move-spec-on-completion! provenance result)
          ;; Trigger meta-loop learning cycle in background
          (trigger-meta-loop-after-workflow! workflow-id outcome-status nil)
          result)
        (finally
          (progress-cleanup)
          (control/release-workflow-control! workflow-id)
          ;; Schedule deferred GC for this workflow's scratch ref — in finally
          ;; so it fires on both normal completion and exception exit paths.
          (enqueue-workflow-gc-best-effort! workflow-id))))
    (catch Object _
      (let [e (:throwable &throw-context)]
        (when-not quiet
          (println (display/colorize :red (messages/t :workflow-runner/spec-execution-failed {:error (ex-message e)})))
          (flush))
        (throw+)))))

;------------------------------------------------------------------------------ Layer 2

;; Resume workflow from checkpointed DAG state
(defn ^{:stratum 2} resume-workflow-from-spec!
  "Resume a previously failed or paused workflow from checkpointed DAG state.

   Arguments:
   - workflow-id: UUID string of the workflow to resume
   - spec: The original spec map (same spec file used for the initial run)
   - opts: Same opts as run-workflow-from-spec!"
  [workflow-id-str spec {:keys [quiet] :or {quiet false} :as opts}]
  (let [resume-ctx (try
                     (workflow-resume/resume-context workflow-id-str)
                     (catch Exception e
                       (when-not quiet
                         (println (display/colorize :red
                                   (messages/t :workflow-runner/resume-state-failed {:error (ex-message e)}))))
                       nil))]
    (when-not quiet
      (println (display/colorize :cyan
                (messages/t :workflow-runner/resuming {:workflow-id workflow-id-str})))
      (println (display/colorize :cyan
                (messages/t :workflow-runner/previously-completed {:count (count (:pre-completed-ids resume-ctx))})))
      (when (seq (:pre-completed-artifacts resume-ctx))
        (println (display/colorize :cyan
                  (messages/t :workflow-runner/recovered-artifacts {:count (count (:pre-completed-artifacts resume-ctx))})))))
    (if (and resume-ctx (seq (:pre-completed-ids resume-ctx)))
      ;; Re-run with pre-completed task IDs injected
      (let [execution-opts (-> (get opts :execution-opts {})
                               (assoc :pre-completed-dag-tasks
                                      (:pre-completed-ids resume-ctx))
                               (assoc :pre-completed-artifacts
                                      (:pre-completed-artifacts resume-ctx)))
            opts-with-resume (assoc opts :execution-opts execution-opts)]
        (run-workflow-from-spec! spec opts-with-resume))
      (do
        (when-not quiet
          (println (display/colorize :yellow
                    (messages/t :workflow-runner/no-completed-tasks))))
        (run-workflow-from-spec! spec opts)))))
