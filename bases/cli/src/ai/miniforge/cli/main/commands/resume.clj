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
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-selection-config :as selection-config]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.workflow-resume.interface :as wr]))

;------------------------------------------------------------------------------ Layer 0
;; Events dir (module-level for test redef-ability)

(def events-dir
  (app-config/events-dir))

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
  (wr/resolve-workflow-identity
    reconstructed
    #(selection-config/resolve-selection-profile :default)))

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
        reconstructed (wr/reconstruct-context events-dir (str workflow-id))]

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

            ;; Resolve workflow interface lazily — avoids pulling the
            ;; workflow component onto the cold-start path.
            load-workflow (requiring-resolve 'ai.miniforge.workflow.interface/load-workflow)
            run-pipeline (requiring-resolve 'ai.miniforge.workflow.interface/run-pipeline)

            {:keys [workflow-type workflow-version]} (resolve-resume-workflow reconstructed)
            {:keys [workflow]} (load-workflow workflow-type workflow-version {})

            ;; Pipeline trimming — delegated to the component
            resume-workflow (wr/trim-pipeline workflow completed-phases)
            remaining-pipeline (:workflow/pipeline resume-workflow)
            _ (when-not quiet
                (if (seq remaining-pipeline)
                  (display/print-info
                    (messages/t :resume/resuming-from-phase
                                {:phase (name (:phase (first remaining-pipeline)))
                                 :count (count remaining-pipeline)}))
                  (display/print-info (messages/t :resume/all-phases-completed))))

            ;; Runtime wiring (CLI concern — stays here)
            event-stream (es/create-event-stream)
            _supervisor (supervisory/attach! event-stream)
            new-workflow-id (random-uuid)
            control-state (es/create-control-state)
            command-poller-cleanup (dashboard/start-command-poller! new-workflow-id control-state)
            llm-client (context/create-llm-client workflow nil quiet)]

        (when-not quiet
          (display/print-info (messages/t :resume/new-workflow-id
                                          {:workflow-id new-workflow-id})))

        (try
          (let [result (run-pipeline resume-workflow
                                     {}
                                     {:llm-backend llm-client
                                      :event-stream event-stream
                                      :control-state control-state
                                      :skip-lifecycle-events false
                                      :pre-completed-dag-tasks (:completed-dag-tasks reconstructed)
                                      :on-phase-start (fn [_ctx interceptor]
                                                        (when-not quiet
                                                          (display/print-info
                                                            (messages/t :resume/phase-starting
                                                                        {:phase (get-in interceptor [:config :phase])}))))
                                      :on-phase-complete (fn [_ctx _interceptor _result] nil)})]
            (when-not quiet
              (display/print-info
                (messages/t :resume/completed-status
                            {:status (:execution/status result)})))
            result)
          (catch Exception e
            (display/print-error (messages/t :resume/failed
                                             {:error (ex-message e)}))
            (throw e))
          (finally
            (when command-poller-cleanup (command-poller-cleanup))))))))
