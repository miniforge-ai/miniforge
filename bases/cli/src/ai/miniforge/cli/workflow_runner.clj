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
   [clojure.edn :as edn]
   [cheshire.core :as json]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.workflow.interface :as workflow]
   [ai.miniforge.workflow.interface.resume :as workflow-resume]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-recommender :as recommender]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.cli.workflow-runner.lifecycle :as lifecycle]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.cli.workflow-runner.paths :as paths]
   [ai.miniforge.cli.workflow-runner.preflight :as preflight]
   [ai.miniforge.cli.workflow-runner.provenance :as provenance]
   [ai.miniforge.cli.workflow-runner.spec-kanban :as spec-kanban]
   [ai.miniforge.cli.workflow-runner.sandbox :as sandbox]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.response.interface :as response]
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
(defn ^{:stratum 0} resolve-workflow-interface []
  {:load-workflow workflow/load-workflow
   :run-pipeline  workflow/run-pipeline})

(defn ^{:stratum 0} create-phase-callbacks [_quiet]
  ;; Phase progress is handled by the event-stream subscription
  ;; (display/start-progress!). Callbacks retained as extension point.
  {})

(defn ^{:stratum 0} load-and-validate-workflow [load-workflow workflow-id version]
  (let [{:keys [workflow validation]} (load-workflow workflow-id version {})]
    (when-not workflow
      (response/throw-anomaly! :anomalies/not-found
                               (messages/t :workflow-runner/not-found {:workflow-id workflow-id})
                               {:workflow-id workflow-id :version version}))
    (when (and validation (not (:valid? validation)))
      (response/throw-anomaly! :anomalies.workflow/invalid-config
                               (messages/t :workflow-runner/validation-failed {:errors (:errors validation)})
                               {:workflow-id workflow-id :validation validation}))
    workflow))

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

(defn ^{:stratum 0} select-workflow-type
  "Select workflow type using LLM recommendation if not explicitly specified.
   Checks :spec/workflow-type first, then :workflow/type as a fallback for
   specs that use the shorter key."
  [spec llm-client quiet]
  (if-let [explicit-type (or (:spec/workflow-type spec)
                             (:workflow/type spec))]
    (do
      (when-not quiet
        (println (display/colorize :cyan (messages/t :workflow-runner/user-specified {:workflow-type (name explicit-type)}))))
      explicit-type)
    (let [recommendation (recommender/recommend-workflow-with-fallback spec llm-client)]
      (when-not quiet
        (println (display/colorize :cyan (messages/t :workflow-runner/auto-selected {:workflow-type (name (:workflow recommendation))})))
        (println (messages/t :workflow-runner/auto-selected-reason {:reasoning (:reasoning recommendation)}))
        (when (= :llm (:source recommendation))
          (println (messages/t :workflow-runner/auto-selected-confidence {:confidence (format "%.0f%%" (* 100 (:confidence recommendation 0.0)))})))
        (println (display/colorize :yellow (messages/t :workflow-runner/auto-selected-override))))
      (:workflow recommendation))))

(def ^{:stratum 0} ^:private workflow-aliases
  "Map legacy/alternate workflow type keywords to their canonical counterparts.
   Many work specs use :standard-sdlc but the only registered workflow is
   :canonical-sdlc."
  {:standard-sdlc :canonical-sdlc})

(defn ^{:stratum 0} format-workflow-listing [workflows]
  (if (empty? workflows)
    (println (messages/t :workflow-runner/no-workflows))
    (do
      (println (display/colorize :cyan (messages/t :workflow-runner/available-workflows)))
      (println (display/colorize :cyan (apply str (repeat 60 "─"))))
      (doseq [{:workflow/keys [id version description type]} workflows]
        (println (str (display/colorize :bold (str "  " (name id)))
                      " (v" version ")"
                      "  " (display/colorize :yellow (messages/t :workflow-runner/workflow-type-label {:type (or type :unknown)}))
                      (when description (str "\n    " description))))
        (println))
      (println (display/colorize :cyan (apply str (repeat 60 "─")))))))

;; Spec-driven execution
(defn- ^{:stratum 0} governed-workflow-id
  "A UUID workflow id for a governed run. The operator control channel
   routes `:pause`/`:resume`/`:cancel` interventions by coercing the
   target id to a UUID, so a run WITHOUT a UUID id is uncontrollable —
   its interventions can't reach its live-runner registry or audit
   trail. Use the spec's `:session-id` when it already is a UUID (or a
   UUID string); otherwise mint one. A PRESENT-but-non-UUID session-id
   is warned about (it was silently discarded); an absent one is the
   normal case and mints quietly."
  [session-id quiet]
  (or (when (uuid? session-id) session-id)
      (when (string? session-id) (parse-uuid session-id))
      (let [fresh (random-uuid)]
        (when (and (some? session-id) (not quiet))
          (println (display/colorize
                    :yellow
                    (messages/t :workflow-runner/non-uuid-session-id
                                {:session-id (pr-str session-id)
                                 :workflow-id (str fresh)}))))
        fresh)))

;; ── Chain-driven execution ─────────────────────────────────────────────────
(defn ^{:stratum 0} resolve-chain-input
  "Resolve chain input from a spec file path or inline JSON."
  [opts]
  (let [spec-path (:spec opts)
        inline-json (:input-json opts)]
    (cond
      inline-json (json/parse-string inline-json true)
      spec-path (let [parsed (edn/read-string (slurp spec-path))
                      enriched (context/decorate-spec-with-runtime-context parsed {})]
                  (context/spec->workflow-input enriched))
      :else {})))

(defn ^{:stratum 0} print-chain-header
  "Print chain execution banner."
  [chain-id chain-def quiet]
  (when-not quiet
    (println)
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-header {:chain-id (name chain-id)})))
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-description {:description (:chain/description chain-def)})))
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-steps {:count (count (:chain/steps chain-def))})))
    (println (display/colorize :cyan (apply str (repeat 60 "─"))))))

(defn ^{:stratum 0} print-chain-result
  "Print chain execution result summary."
  [result quiet]
  (when-not quiet
    (let [steps (:chain/step-results result)
          duration (:chain/duration-ms result)]
      (println)
      (println (display/colorize :cyan (apply str (repeat 60 "─"))))
      (if (phase/succeeded? result)
        (println (display/colorize :green (messages/t :workflow-runner/chain-completed {:count (count steps) :duration duration})))
        (let [failed-step (some #(when (phase/failed? %) (:step/id %)) steps)]
          (println (display/colorize :red (messages/t :workflow-runner/chain-failed-at {:step (when failed-step (name failed-step))}))))))))

(defn ^{:stratum 0} list-chains!
  "List all available chain definitions."
  []
  (try
    (let [chains (workflow/list-chains)]
      (if (empty? chains)
        (println (messages/t :workflow-runner/no-chains))
        (do
          (println (display/colorize :cyan (messages/t :workflow-runner/available-chains)))
          (println (display/colorize :cyan (apply str (repeat 60 "─"))))
          (doseq [{:keys [id version description steps]} chains]
            (println (str (display/colorize :bold (str "  " (name id)))
                          " (v" version ")"
                          "  " (messages/t :workflow-runner/chain-steps-label {:steps steps})))
            (when description
              (println (str "    " description)))
            (println))
          (println (display/colorize :cyan (apply str (repeat 60 "─")))))))
    (catch Exception e
      (println (display/colorize :red (messages/t :workflow-runner/list-chains-failed {:error (ex-message e)})))
      (throw e))))

(defn- ^{:stratum 0} assert-runtime-alignment!
  [spec context]
  (paths/assert-valid-source-root! context)
  (paths/assert-execution-worktree! context)
  (paths/assert-source-dir-alignment! spec context))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-workflow-alias
  "Resolve a workflow type through the alias map. Returns the canonical type
   if an alias exists, otherwise returns the type unchanged."
  [workflow-type]
  (get workflow-aliases workflow-type workflow-type))

(defn ^{:stratum 1} list-workflows-from-resources []
  (let [list-workflows workflow/list-workflows]
    (->> (list-workflows)
         (sort-by (juxt :workflow/id :workflow/version))
         format-workflow-listing)))

(defn ^{:stratum 1} run-chain!
  "Execute a chain of workflows.

   Arguments:
   - chain-id: Chain identifier keyword (e.g. :reporting-chain)
   - opts: {:version \"latest\" :spec \"spec.edn\" :input-json \"{...}\" :quiet false}"
  [chain-id opts]
  (let [quiet (get opts :quiet false)
        version (get opts :version "latest")]
    (try
      (let [chain-result (workflow/load-chain chain-id version)
            chain-def (:chain chain-result)
            chain-input (resolve-chain-input opts)
            event-stream (es/create-event-stream)
            _supervisor (supervisory/attach! event-stream)
            ;; N15-6: see meta-loop attach above for rationale.
            _correlator (correlator/attach! event-stream)
            llm-client (context/create-llm-client nil nil quiet)
            callbacks (create-phase-callbacks quiet)
            chain-run-id (random-uuid)
            control-state (es/create-control-state)
            context (context/create-workflow-context {:callbacks callbacks
                                                      :event-stream event-stream
                                                      :llm-client llm-client
                                                      :quiet quiet
                                                      :workflow-id chain-run-id
                                                      :workflow-type chain-id
                                                      :workflow-version version
                                                      :spec-title (str "Chain: " (name chain-id))
                                                      :control-state control-state})
            progress-cleanup (display/start-progress! event-stream quiet)]
        (print-chain-header chain-id chain-def quiet)
        (dashboard/print-dashboard-status! quiet)
        (preflight/run-backend-preflight! quiet llm-client context)
        (try
          (control/register-workflow-control! chain-run-id control-state event-stream)
          (let [result (workflow/run-chain chain-def chain-input context)]
            (print-chain-result result quiet)
            result)
          (finally
            (progress-cleanup)
            (control/release-workflow-control! chain-run-id))))
      (catch Exception e
        (when-not quiet
          (println (display/colorize :red (messages/t :workflow-runner/chain-execution-failed {:error (ex-message e)}))))
        (throw e)))))

(defn ^{:stratum 1} run-workflow! [workflow-id {:keys [version output quiet event-stream dashboard-url]
                                    :or {version "latest" output :pretty quiet false}
                                    :as opts}]
  ;; Piggyback deferred GC on each workflow start — deletes scratch refs
  ;; from finished workflows that are older than the 7-day retention window.
  (run-gc-pass-best-effort!)
  (try
    (let [{:keys [load-workflow run-pipeline]} (resolve-workflow-interface)
          ;; Create event stream if not provided (dashboard-url takes precedence)
          es (or event-stream
                 (when-not dashboard-url
                   (try
                     (es/create-event-stream)
                     (catch Exception _ nil))))]
      (display/print-workflow-header workflow-id version quiet)
      (let [workflow-input (context/resolve-input opts)
            workflow (load-and-validate-workflow load-workflow workflow-id version)
            artifact-store (execution/create-artifact-store quiet)
            callbacks (create-phase-callbacks quiet)
            ;; Pass dashboard-url in callbacks if provided
            callbacks-with-url (cond-> callbacks
                                 dashboard-url (assoc :dashboard-url dashboard-url))
            progress-cleanup (display/start-progress! es quiet)
            ;; BD-2b sub-3a: per-workflow manifest. Stamps an :active /
            ;; :live manifest before the pipeline starts and keeps the
            ;; owner lease renewed via a heartbeat while alive. The
            ;; happy path below marks :completed/:failed after drain;
            ;; the finally falls back to :cancelled if neither fired.
            manifest-handle (lifecycle/start-workflow-manifest! workflow-id es)]
        (try
          (let [result (execution/execute-workflow-pipeline run-pipeline workflow workflow-input callbacks-with-url artifact-store es)]
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
            (let [shutdown (lifecycle/event-stream-shutdown! es workflow-id opts)]
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-or-create-workflow [load-workflow workflow-type workflow-version]
  (let [workflow-type (resolve-workflow-alias workflow-type)]
    (try+
      (load-and-validate-workflow load-workflow workflow-type workflow-version)
      (catch [:anomaly/category :anomalies/not-found] _
        (case workflow-type
          :test-only
          {:workflow/id :test-only
           :workflow/version "inline"
           :workflow/name "Test Generation"
           :workflow/pipeline [{:phase :verify} {:phase :done}]
           :workflow/config {:max-tokens 20000 :max-iterations 10}}

          :comment-fix
          {:workflow/id :comment-fix
           :workflow/version "inline"
           :workflow/name "Comment Fix"
           :workflow/pipeline [{:phase :implement :gates [:syntax :lint :no-secrets]}
                               {:phase :done}]
           :workflow/config {:max-tokens 20000 :max-iterations 5}}

          (throw+))))))

(defn ^{:stratum 2} list-workflows! []
  (try
    (list-workflows-from-resources)
    (catch Exception e
      (println (display/colorize :red (messages/t :workflow-runner/list-failed {:error (ex-message e)})))
      (throw e))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} run-workflow-from-spec! [spec {:keys [quiet] :or {quiet false} :as opts}]
  ;; Piggyback deferred GC on each spec-driven workflow start.
  (run-gc-pass-best-effort!)
  (try+
    (let [{:keys [load-workflow run-pipeline]} (resolve-workflow-interface)
          ;; Create initial LLM client for workflow selection
          backend-override (:backend opts)
          selection-llm-client (context/create-llm-client nil spec quiet backend-override)
          workflow-type (select-workflow-type spec selection-llm-client quiet)
          workflow-version (get spec :spec/workflow-version "latest")
          workflow (load-or-create-workflow load-workflow workflow-type workflow-version)
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
          workflow-id (governed-workflow-id
                       (get-in enriched-spec [:spec/metadata :session-id]) quiet)
          ;; Control state the governed operator channel flips for
          ;; :pause / :resume / :cancel interventions.
          control-state (es/create-control-state)
          ;; Create workflow-specific LLM client for execution
          llm-client (context/create-llm-client workflow spec quiet backend-override)
          callbacks (create-phase-callbacks quiet)
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

;------------------------------------------------------------------------------ Layer 4

;; Resume workflow from checkpointed DAG state
(defn ^{:stratum 4} resume-workflow-from-spec!
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
