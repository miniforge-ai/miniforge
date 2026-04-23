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

(ns ai.miniforge.workflow.context
  "Execution context management.

   Handles creation and manipulation of workflow execution context,
   including FSM state transitions and metrics merging.

   ## Execution Context Schema

   The execution context is a flat map passed through the workflow pipeline.
   Keys use the :execution/ namespace prefix unless otherwise noted.

   ### Identity & Status
   :execution/id               — UUID for this workflow run
   :execution/workflow         — Workflow configuration map
   :execution/workflow-id      — UUID from workflow config
   :execution/workflow-version — Version string from workflow config
   :execution/status           — Derived from the authoritative execution
                                 machine snapshot:
                                 :pending | :running | :paused | :completed |
                                 :failed | :cancelled |
                                 :completed-with-warnings
   :execution/fsm-machine      — Compiled execution machine for this run
   :execution/fsm-state        — Authoritative machine snapshot

   ### Input / Output
   :execution/input            — Input data for the workflow (task spec, etc.)
   :execution/output           — Final workflow output (set on completion)

   ### Environment Model (N6)
   The workflow runs inside an acquired execution environment (Docker container
   or git worktree). Three keys track the environment lifecycle:

   :execution/executor         — TaskExecutor instance (Docker or worktree
                                 strategy). Carries the release-environment!
                                 method used at workflow teardown.
   :execution/environment-id   — Opaque ID for the acquired execution
                                 environment; passed to release-environment!
                                 at workflow teardown.
   :execution/worktree-path    — Resolved working directory within the
                                 environment; agents write code directly here.

   Code changes are NOT serialized into phase results. Instead they live in
   the environment's git working tree and are captured via PR diff at release
   time (see Phase Result Schema below).

   ### Phase Execution
   :execution/phase-results    — Map of phase-name → phase result (see below)
   :execution/current-phase    — Derived projection of the active machine state
   :execution/phase-index      — Derived projection of the active machine state
   :execution/redirect-count   — Derived from machine context

   ### Phase Result Schema
   Entries in :execution/phase-results follow the shape:

     {:status         :success | :failure | :already-implemented | :retrying
      :environment-id  opaque ID of the environment where changes landed
      :summary         agent's human-readable description of what was done
      :metrics         map of phase-specific metrics (see per-phase shapes)}

   Phase-specific :metrics content:
     :implement  → {:tokens N :duration-ms N}
     :verify     → {:tokens N :duration-ms N :pass-count N :fail-count N
                    :test-output string}
     :release    → {:tokens N :duration-ms N :pr-url string :branch string
                    :commit-sha string}

   NOTE: Phase results do NOT carry :code/files or any serialized code.
   Code provenance is derived from the PR diff at release time.

   ### Artifacts & Tracking
   :execution/artifacts        — Vector of lightweight provenance artifacts
                                 produced by phases (not serialized code)
   :execution/files-written    — Set of file paths written (supervision
                                 tracking; populated from environment)
   :execution/metrics          — Accumulated metrics:
                                 {:tokens N :cost-usd N :duration-ms N}
   :execution/started-at       — System time millis at workflow start
   :execution/ended-at         — System time millis at workflow end

   ### Error Handling
   :execution/errors           — DEPRECATED: use :execution/response-chain
   :execution/response-chain   — Structured response chain (per-phase
                                 success/failure tracking)

   ### Supervision Support
   :execution/supervision-runtime — Runtime for workflow health supervision
   :execution/streaming-activity  — Transient streaming activity tracking

   ### Pass-Through from opts
   :llm-backend    — LLM backend configuration
   :artifact-store — Artifact persistence store
   :knowledge-store — Knowledge base store
   :event-stream   — Event stream for telemetry"
  (:require [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.fsm :as fsm]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [ai.miniforge.agent.interface :as agent]))

;------------------------------------------------------------------------------ Internal helpers

(defn sync-machine-projections
  "Project workflow fields from the authoritative machine snapshot."
  [ctx]
  (let [machine (:execution/fsm-machine ctx)
        fsm-state (:execution/fsm-state ctx)
        projection (when (and machine fsm-state)
                     (fsm/execution-projection machine fsm-state))
        ctx' (cond-> (merge ctx projection)
               (and (= :completed (:execution/status projection))
                    (:execution/completed-with-warnings? ctx))
               (assoc :execution/status :completed-with-warnings))]
    ctx'))

(defn transition-execution
  "Apply an event to the authoritative execution machine and refresh projections."
  [ctx event]
  (if-let [machine (:execution/fsm-machine ctx)]
    (let [fsm-state (:execution/fsm-state ctx)
          next-state (fsm/transition-execution machine fsm-state event)
          next-ctx (sync-machine-projections
                    (assoc ctx :execution/fsm-state next-state))]
      (cond-> next-ctx
        (and (contains? #{:completed :failed :cancelled}
                        (:execution/status next-ctx))
             (nil? (:execution/ended-at next-ctx)))
        (assoc :execution/ended-at (System/currentTimeMillis))))
    ctx))

(defn active-or-last-phase
  "Return the active phase when present, otherwise the last completed phase in
   workflow pipeline order."
  [ctx]
  (let [phase-results (:execution/phase-results ctx)
        pipeline (:workflow/pipeline (:execution/workflow ctx))
        last-recorded-phase (some->> pipeline
                                     (map :phase)
                                     (filter #(contains? phase-results %))
                                     last)]
    (if-some [current-phase (:execution/current-phase ctx)]
      current-phase
      last-recorded-phase)))

;------------------------------------------------------------------------------ Context operations

(defn create-context
  "Create initial execution context.

   Arguments:
   - workflow: Workflow configuration
   - input: Input data for the workflow
   - opts: Execution options (including :llm-backend, :artifact-store, callbacks)

   Returns execution context map with FSM state initialized."
  [workflow input opts]
  (let [execution-machine (fsm/compile-execution-machine workflow)
        fsm-state (let [initial-state (fsm/initialize-execution execution-machine)]
                    (fsm/start-execution execution-machine initial-state))
        ;; Initialize live workflow supervision runtime.
        supervisors (monitoring/create-supervisors workflow)
        supervision-runtime (agent/create-supervision-coordinator supervisors)]
    (sync-machine-projections
     (merge
      {:execution/id (random-uuid)
       :execution/workflow workflow
       :execution/workflow-id (:workflow/id workflow)
       :execution/workflow-version (:workflow/version workflow)
       :execution/fsm-machine execution-machine
       :execution/fsm-state fsm-state
       :execution/input input
       :execution/artifacts []
       :execution/errors []  ; DEPRECATED: Use :execution/response-chain instead
       :execution/response-chain (response/create (:workflow/id workflow))
       :execution/phase-results {}
       :execution/output nil
       :execution/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}
       :execution/started-at (System/currentTimeMillis)
       :execution/opts opts
       ;; Live workflow supervision runtime
       :execution/supervision-runtime supervision-runtime
       ;; Transient state for supervision checks
       :execution/streaming-activity []
       :execution/files-written #{}}
      ;; Merge opts into top-level context so :llm-backend is accessible to agents
      (select-keys opts [:llm-backend :artifact-store :knowledge-store
                         :on-phase-start :on-phase-complete
                         :executor :environment-id :sandbox-workdir
                         :on-chunk :event-stream :worktree-path])))))

(defn merge-metrics
  "Merge phase metrics into execution metrics.
   Nil-safe: treats nil values as 0 to prevent NPE from merge-with +."
  [exec-metrics phase-metrics]
  (merge-with (fn [a b] (+ (or a 0) (or b 0)))
              exec-metrics
              (select-keys phase-metrics [:tokens :cost-usd :duration-ms])))

(defn transition-to-completed
  "Transition workflow to completed state using FSM."
  [ctx]
  (-> (if (:execution/fsm-machine ctx)
        (transition-execution ctx :complete)
        (assoc ctx :execution/status :completed))
      (assoc :execution/ended-at (System/currentTimeMillis))))

(defn transition-to-failed
  "Transition workflow to failed state using FSM."
  [ctx]
  (-> (if (:execution/fsm-machine ctx)
        (transition-execution ctx :fail)
        (assoc ctx :execution/status :failed))
      (assoc :execution/ended-at (System/currentTimeMillis))))
