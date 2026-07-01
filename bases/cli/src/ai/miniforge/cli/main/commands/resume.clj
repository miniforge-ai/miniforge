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
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.workflow.interface :as workflow]
   [ai.miniforge.workflow-resume.interface :as wr]))

;------------------------------------------------------------------------------ Layer 0
;; Events dir (module-level for test redef-ability)

(def events-dir
  (app-config/events-dir))

(def load-workflow
  "Workflow loader dependency, exposed as a var so tests can rebind the
   CLI boundary without dynamic namespace resolution."
  workflow/load-workflow)

(def run-pipeline
  "Workflow runner dependency, exposed as a var so tests can rebind the
   CLI boundary without dynamic namespace resolution."
  workflow/run-pipeline)

(defn- anomaly-category
  [a]
  (case (:anomaly/type a)
    :not-found :anomalies/not-found
    :invalid-input :anomalies/incorrect
    :anomalies/fault))

(defn- throw-resume-anomaly!
  [a]
  (when (anomaly/anomaly? a)
    (response/throw-anomaly! (anomaly-category a)
                             (:anomaly/message a)
                             (:anomaly/data a))))

;------------------------------------------------------------------------------ Layer 1
;; Thin delegations kept for compatibility with existing callers/tests

(defn read-event-file
  "Read events for a workflow. Thin wrapper — the actual replay lives
   in `event-stream/reader`. Prefer calling the component directly in
   new code."
  [workflow-id]
  (es/read-workflow-events-by-id events-dir workflow-id))

(defn resolve-resume-workflow
  "Resolve workflow identity for a resumed run, using the CLI's
   default selection profile as fallback. Thin wrapper over the
   component's `resolve-workflow-identity`."
  [reconstructed]
  (let [result (wr/resolve-workflow-identity
                reconstructed
                #(selection-config/resolve-selection-profile :default))]
    (throw-resume-anomaly! result)
    result))

;------------------------------------------------------------------------------ Layer 1.5
;; Status semantics

(def terminal-statuses
  "Workflow execution statuses that mean the run has actually finished —
   either successfully or definitively failed. Anything outside this set
   (`:running`, `:pending`, `:paused`, nil) means the runner returned
   without advancing the FSM to a terminal state, which is the silent
   fast-fail blocker filed as work/workflow-resume-status-handling.spec.edn."
  #{:completed :completed-with-warnings :failed :aborted :cancelled})

(defn terminal-status?
  "True when `status` represents a finished workflow."
  [status]
  (contains? terminal-statuses status))

(defn- resume-print-phase
  "Pick the phase name to render in `Resuming from phase: X`. Prefer
   the FSM machine snapshot's recorded `:execution/current-phase` so
   the print reflects where the run actually parked — falling back to
   the first remaining-pipeline entry only when no snapshot exists
   (cold pipeline-trim resume)."
  [machine-snapshot remaining-pipeline]
  (or (when machine-snapshot
        (some-> (:execution/current-phase machine-snapshot) name))
      (some-> (:phase (first remaining-pipeline)) name)))

;------------------------------------------------------------------------------ Layer 2
;; Public API — invoked by both `mf resume <id>` and `mf run --resume`

(defn resume-workflow
  "Resume a workflow from its last checkpoint.

   Reconstructs context via the workflow-resume component, trims the
   pipeline to remaining phases, and re-runs via workflow/interface."
  [workflow-id opts]
  (let [quiet (:quiet opts false)
        _ (when-not quiet
            (display/print-info (messages/t :resume/resuming
                                            {:workflow-id workflow-id})))
        reconstructed (wr/reconstruct-context events-dir (str workflow-id))
        _ (throw-resume-anomaly! reconstructed)]

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

            identity (resolve-resume-workflow reconstructed)
            _ (throw-resume-anomaly! identity)
            {:keys [workflow-type workflow-version]} identity
            {:keys [workflow]} (load-workflow workflow-type workflow-version {})

            machine-snapshot (:machine-snapshot reconstructed)
            failed-checkpoint? (and machine-snapshot (:failed? reconstructed))
            resume-workflow (if (and machine-snapshot (not failed-checkpoint?))
                              workflow
                              (wr/trim-pipeline workflow completed-phases))
            _ (throw-resume-anomaly! resume-workflow)
            remaining-pipeline (:workflow/pipeline resume-workflow)
            resume-run-id (or (:execution/id machine-snapshot) (random-uuid))
            _ (when-not quiet
                (if machine-snapshot
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
            command-poller-cleanup (dashboard/start-command-poller! resume-run-id control-state)
            llm-client (context/create-llm-client workflow nil quiet)]

        (try
          (let [result (run-pipeline resume-workflow
                                     {}
                                     {:llm-backend llm-client
                                      :event-stream event-stream
                                      :control-state control-state
                                      :resume-machine-snapshot machine-snapshot
                                      :resume-reset-terminal? failed-checkpoint?
                                      :resume-phase-results (:phase-results reconstructed)
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
            (when command-poller-cleanup (command-poller-cleanup))))))))
