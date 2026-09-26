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
(ns ai.miniforge.cli.main.commands.resume
  "CLI adapter for workflow resume.

   Domain logic lives in the `workflow-resume` component — this
  namespace is the thin CLI shell: parses args, wires runtime
   (event-stream, supervisory, LLM client), prints progress, invokes
   `run-pipeline` on the trimmed workflow.

   Exposed both as `mf resume <id>` (first-class subcommand) and
   — for backward compatibility — via the `--resume <id>` flag on
   `mf run`."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-selection-config :as selection-config]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.workflow.interface :as workflow]
   [ai.miniforge.workflow-resume.interface :as wr]))

;------------------------------------------------------------------------------ Layer 0

;; Events dir (module-level for test redef-ability)
(def ^{:stratum 0} events-dir
  (app-config/events-dir))

(def ^{:stratum 0} load-workflow
  "Workflow loader dependency, exposed as a var so tests can rebind the
   CLI boundary without dynamic namespace resolution."
  workflow/load-workflow)

(def ^{:stratum 0} run-pipeline
  "Workflow runner dependency, exposed as a var so tests can rebind the
   CLI boundary without dynamic namespace resolution."
  workflow/run-pipeline)

(defn- ^{:stratum 0} anomaly-category
  [a]
  (case (:anomaly/type a)
    :not-found :anomalies/not-found
    :invalid-input :anomalies/incorrect
    :anomalies/fault))

;; Status semantics
(def ^{:stratum 0} terminal-statuses
  "Workflow execution statuses that mean the run has actually finished —
   either successfully or definitively failed. Anything outside this set
   (`:running`, `:pending`, `:paused`, nil) means the runner returned
   without advancing the FSM to a terminal state, which is the silent
   fast-fail blocker filed as work/workflow-resume-status-handling.spec.edn."
  #{:completed :completed-with-warnings :failed :aborted :cancelled})

(defn- ^{:stratum 0} resume-print-phase
  "Pick the phase name to render in `Resuming from phase: X`. Prefer
   the FSM machine snapshot's recorded `:execution/current-phase` so
   the print reflects where the run actually parked — falling back to
   the first remaining-pipeline entry only when no snapshot exists
   (cold pipeline-trim resume)."
  [machine-snapshot remaining-pipeline]
  (or (when machine-snapshot
        (some-> (:execution/current-phase machine-snapshot) name))
      (some-> (:phase (first remaining-pipeline)) name)))

(defn- ^{:stratum 0} recorded-phase?
  "True when the run recorded `phase`: completed it, has a result for it,
   or its FSM snapshot parked on it."
  [reconstructed phase]
  (or (boolean (some #{phase} (:completed-phases reconstructed)))
      (contains? (:phase-results reconstructed) phase)
      (= phase (get-in reconstructed [:machine-snapshot :execution/current-phase]))))

(defn- ^{:stratum 0} rewind-to-phase
  "`--from-phase`: the same rewind the operator's `:retry-from-phase` plan
   describes, to a phase `p`. Only `kept`, the completed phases before
   `p`, stay completed, so `p` and everything after it run again, from
   those phases' state.

   The FSM snapshot is dropped: it is parked after `p`, and restoring it
   would ignore the rewind. What the re-run keeps of it is the run's
   input and acting authority; the runner starts a fresh machine at `p`
   holding only the kept phases' results.

   The old run's DAG work is dropped too. It is only ever consumed when
   the plan phase runs and executes its DAG: after a rewind to the plan
   (or earlier) it would skip the re-planned tasks that share an id,
   and after a rewind past it the DAG does not run again.

   The workspace restored is the latest of `checkpoints` (the run's
   persisted workspaces) made by a phase that stays completed — never
   one made by `p` or later, which would start the re-run on its own
   output. With none, the run starts from a fresh workspace: the only
   recorded states are after the rewind point, and before the first
   phase there is nothing else to restore."
  [reconstructed kept checkpoints]
  (let [workspace (last (filter (comp (set kept) :phase) checkpoints))
        {:execution/keys [input acting]} (:machine-snapshot reconstructed)]
    (-> reconstructed
        (dissoc :machine-snapshot)
        (assoc :completed? false
               :completed-phases kept
               :phase-results (select-keys (:phase-results reconstructed) kept)
               :completed-dag-tasks #{}
               :completed-dag-artifacts []
               :workspace-checkpoint workspace)
        (cond-> input (assoc :input input)
                acting (assoc :acting acting)))))

(defn- ^{:stratum 0} inherited-workspaces
  "The workspace checkpoints a new attempt carries over from the run it
   resumes: all of them, or on a rewind those of the phases it keeps."
  [checkpoints from-phase kept]
  (cond->> checkpoints from-phase (filter (comp (set kept) :phase))))

(defn- ^{:stratum 0} uuid-option
  "The UUID an option names, nil when it is absent; refused when present
   but not a UUID."
  [flag value]
  (let [parsed (some-> value str parse-uuid)]
    (if (and value (nil? parsed))
      (response/throw-anomaly! :anomalies/incorrect
                               (messages/t :resume/not-a-uuid {:flag flag :value value})
                               {:flag flag})
      parsed)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} throw-resume-anomaly!
  [a]
  (when (anomaly/anomaly? a)
    (response/throw-anomaly! (anomaly-category a)
                             (:anomaly/message a)
                             (merge (:anomaly/data a)
                                    (select-keys a [:anomaly/type
                                                    :anomaly/subtype
                                                    :anomaly/at])))))

;; Thin delegations kept for compatibility with existing callers/tests
(defn ^{:stratum 1} read-event-file
  "Read events for a workflow. Thin wrapper — the actual replay lives
   in `event-stream/reader`. Prefer calling the component directly in
   new code."
  [workflow-id]
  (es/read-workflow-events-by-id events-dir workflow-id))

(defn ^{:stratum 1} terminal-status?
  "True when `status` represents a finished workflow."
  [status]
  (contains? terminal-statuses status))

(defn- ^{:stratum 1} apply-from-phase
  "Rewind to `from-phase` when one was requested. A phase the run never
   recorded is refused, not guessed at, and so is a rewind the shared
   rule refuses (`wr/rewind-refusal`, as the operator's retry applies it).
   `checkpoints` is a thunk for the run's persisted workspaces, read only
   for a rewind."
  [reconstructed from-phase checkpoints]
  (let [refusal (when from-phase (wr/rewind-refusal reconstructed from-phase))]
    (cond
      (nil? from-phase) reconstructed
      (not (recorded-phase? reconstructed from-phase))
      (response/throw-anomaly! :anomalies/incorrect
                               (messages/t :resume/unknown-phase {:phase (name from-phase)})
                               {:from-phase from-phase})
      refusal
      (response/throw-anomaly! :anomalies/unsupported
                               (messages/t :resume/rewind-without-results
                                           {:phase (name from-phase)
                                            :phases (str/join ", " (map name (:resume/phases refusal)))})
                               (assoc refusal :from-phase from-phase))
      :else (rewind-to-phase reconstructed
                             (wr/rewind-kept-phases reconstructed from-phase)
                             (checkpoints)))))

(defn- ^{:stratum 1} run-id-for
  "The id the resumed run executes under: `--run-id`, else the restored
   snapshot's own id, else a fresh one. A `--run-id` other than the
   snapshot's starts a new attempt: the snapshot's state under that id,
   so its events never land beside the finished run's (archived) ones.
   The operator's resume launcher passes a fresh one on every retry. A
   `--run-id` naming another run that has events under `events-dir` or a
   checkpoint is refused: the attempt would write into that run."
  [events-dir workflow-id machine-snapshot run-id-opt]
  (let [requested (uuid-option "--run-id" run-id-opt)
        snapshot-id (:execution/id machine-snapshot)]
    (if (and requested
             (not (contains? #{(str workflow-id) (str snapshot-id)} (str requested)))
             (or (seq (es/read-workflow-events-by-id events-dir (str requested)))
                 (try (workflow/load-checkpoint-data (str requested)) (catch Exception _ true))))
      (response/throw-anomaly! :anomalies/conflict
                               (messages/t :resume/run-id-taken {:run-id run-id-opt})
                               {:run-id run-id-opt})
      (or requested snapshot-id (random-uuid)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-resume-workflow
  "Resolve workflow identity for a resumed run, using the CLI's
   default selection profile as fallback. Thin wrapper over the
   component's `resolve-workflow-identity`."
  [reconstructed]
  (let [result (wr/resolve-workflow-identity
                reconstructed
                #(selection-config/resolve-selection-profile :default))]
    (throw-resume-anomaly! result)
    result))

;------------------------------------------------------------------------------ Layer 3

;; Public API — invoked by both `mf resume <id>` and `mf run --resume`
(defn ^{:stratum 3} resume-workflow
  "Resume a workflow from its last checkpoint.

   Reconstructs context via the workflow-resume component, trims the
   pipeline to remaining phases, and re-runs via workflow/interface."
  [workflow-id opts]
  (let [quiet (:quiet opts false)
        _ (when-not quiet
            (display/print-info (messages/t :resume/resuming
                                            {:workflow-id workflow-id})))
        recorded (wr/reconstruct-context events-dir (str workflow-id))
        _ (throw-resume-anomaly! recorded)
        reconstructed (apply-from-phase recorded (:from-phase opts)
                                        #(wr/extract-workspace-checkpoints (read-event-file workflow-id)))
        ;; Checked before a completed run is reported done: an invalid
        ;; request is refused, not answered "already completed".
        resume-run-id (run-id-for events-dir workflow-id (:machine-snapshot reconstructed) (:run-id opts))
        ;; `--correlation-id` lands on the run's lifecycle events; the
        ;; operator's launcher passes its intervention id and waits for
        ;; it. Absent, none is imposed and the runner's default applies
        ;; (the run's own id).
        correlation-id (uuid-option "--correlation-id" (:correlation-id opts))]

    (if (:completed? reconstructed)
      (do (display/print-info (messages/t :resume/already-completed)) nil)

      (let [completed-phases (:completed-phases reconstructed)
            _ (when-not quiet
                (display/print-info
                  (messages/t :resume/completed-phases
                              {:phases (if (seq completed-phases)
                                         (str/join ", " (map name completed-phases))
                                         (messages/t :resume/completed-phases-none))}))
                (display/print-info
                  (messages/t :resume/events-found
                              {:count (:event-count reconstructed)})))

            ;; From the run as recorded: a rewind drops the snapshot, which
            ;; names the workflow when no spec was recorded.
            identity (resolve-resume-workflow recorded)
            _ (throw-resume-anomaly! identity)
            {:keys [workflow-type workflow-version]} identity
            {:keys [workflow]} (load-workflow workflow-type workflow-version {})

            restored-snapshot (:machine-snapshot reconstructed)
            ;; Another id than the run's own: a new attempt, whose events
            ;; and cost are its own.
            new-attempt? (not= (str resume-run-id) (str (or (:execution/id restored-snapshot) workflow-id)))
            machine-snapshot (some-> restored-snapshot
                                     (assoc :execution/id resume-run-id)
                                     (cond-> new-attempt? (assoc :execution/metrics {:tokens 0 :cost-usd 0.0
                                                                                     :duration-ms 0})))
            failed-checkpoint? (and machine-snapshot (:failed? reconstructed))
            resume-workflow (if (and machine-snapshot (not failed-checkpoint?))
                              workflow
                              (wr/trim-pipeline workflow completed-phases))
            _ (throw-resume-anomaly! resume-workflow)
            remaining-pipeline (:workflow/pipeline resume-workflow)
            _ (when-not quiet
                (if (= (str resume-run-id) (str (:execution/id restored-snapshot)))
                  (display/print-info
                    (messages/t :resume/restored-workflow-id
                                {:workflow-id resume-run-id}))
                  (display/print-info
                    (messages/t :resume/new-workflow-id
                                {:workflow-id resume-run-id})))
                (if (seq remaining-pipeline)
                  (display/print-info
                    (messages/t :resume/resuming-from-phase
                                {:phase (or (resume-print-phase machine-snapshot
                                                                remaining-pipeline)
                                            "?")
                                 :count (count remaining-pipeline)}))
                  (display/print-info (messages/t :resume/all-phases-completed))))

            ;; Runtime wiring (CLI concern — stays here)
            event-stream (es/create-event-stream)
            _supervisor (supervisory/attach! event-stream)
            ;; N15-6: route routing-causality through the witness surface.
            ;; Mirrors the meta-loop and workflow-runner attach sites; the
            ;; correlator emits `:supervisory/automation-edge-upserted`
            ;; alongside the supervisory snapshots.
            _correlator (correlator/attach! event-stream)
            control-state (es/create-control-state)
            llm-client (context/create-llm-client workflow nil quiet)
            ;; A new attempt keeps the run's workspace checkpoints under its
            ;; own id, so a retry or rewind of the attempt can restore them.
            _ (when new-attempt?
                (doseq [checkpoint (inherited-workspaces
                                    (wr/extract-workspace-checkpoints (read-event-file workflow-id))
                                    (:from-phase opts)
                                    (:completed-phases reconstructed))]
                  (es/publish! event-stream (es/workspace-persisted event-stream resume-run-id checkpoint))))]

        (try
          ;; Governed control path: the resumed run gets the same
          ;; pause/resume/cancel reach as a fresh one, released below.
          (control/register-workflow-control! resume-run-id
                                              control-state
                                              event-stream)
          (let [result (run-pipeline resume-workflow
                                     ;; The run's input on a rewind; a restored snapshot carries its own.
                                     (get reconstructed :input {})
                                     {:llm-backend llm-client
                                      :acting (:acting reconstructed)
                                      ;; The id announced and registered
                                      ;; above; without it a snapshot-less
                                      ;; resume minted a second id.
                                      :workflow-id resume-run-id
                                      :workflow-run/correlation-id correlation-id
                                      :event-stream event-stream
                                      :control-state control-state
                                      :resume-machine-snapshot machine-snapshot
                                      :resume-reset-terminal? failed-checkpoint?
                                      ;; Never event telemetry; a rewind has
                                      ;; already cut them to the kept phases.
                                      :resume-phase-results (when (wr/checkpointed-phase-results recorded)
                                                              (:phase-results reconstructed))
                                      :resume-workspace (:workspace-checkpoint reconstructed)
                                      :skip-lifecycle-events false
                                      :pre-completed-dag-tasks (:completed-dag-tasks reconstructed)
                                      :pre-completed-artifacts (:completed-dag-artifacts reconstructed)
                                      :on-phase-start (fn [_ctx interceptor]
                                                        (when-not quiet
                                                          (display/print-info
                                                           (messages/t :resume/phase-starting
                                                                       {:phase (get-in interceptor [:config :phase])}))))
                                      :on-phase-complete (fn [_ctx _interceptor _result] nil)})
                final-status (:execution/status result)]
            (when-not quiet
              (display/print-info
                (messages/t :resume/completed-status
                            {:status final-status})))
            (when-not (terminal-status? final-status)
              ;; Non-terminal status means run-pipeline returned without
              ;; advancing the FSM to a terminal state. The CLI used to
              ;; print this and exit 0 — silently losing the prior
              ;; session's plan/explore/verify token spend. Throw so
              ;; main exits non-zero and dogfood drivers see the failure.
              (response/throw-anomaly!
                :anomalies.workflow/resume-non-terminal
                (messages/t :resume/non-terminal-status
                            {:status final-status
                             :workflow-id workflow-id})
                {:workflow-id workflow-id
                 :status final-status}))
            result)
          (catch Exception e
            (display/print-error (messages/t :resume/failed
                                             {:error (ex-message e)}))
            (throw e))
          (finally
            (control/release-workflow-control! resume-run-id)))))))
