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

(ns ai.miniforge.workflow-resume.interface
  "Public API for workflow resume — reconstructing execution context
   from recorded events and preparing a trimmed workflow for re-run.

   Adapters (CLI, HTTP API, dashboard) compose runtime wiring on top;
   this interface is pure.

   Typical use:

       (require '[ai.miniforge.workflow-resume.interface :as wr])

       (def ctx (wr/reconstruct-context events-dir workflow-id))
       (def identity (wr/resolve-workflow-identity ctx select-default-fn))
       (def workflow (load-workflow (:workflow-type identity)
                                    (:workflow-version identity)))
       (def resumed (wr/trim-pipeline workflow (:completed-phases ctx)))
       ;; adapters call run-pipeline on `resumed` with the ctx's
       ;; :completed-dag-tasks / :completed-dag-artifacts threaded into
       ;; :pre-completed-dag-tasks / :pre-completed-artifacts"
  (:require
   [ai.miniforge.workflow-resume.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Pure extractors

(def completed?
  "True when a reconstructed workflow context completed successfully.
   Arg: the context map from `reconstruct-context`. Returns boolean."
  core/completed?)

(def failed?
  "True when a reconstructed workflow context has failed.
   Arg: the context map from `reconstruct-context`. Returns boolean."
  core/failed?)

(def paused?
  "True when a reconstructed workflow context reflects a paused DAG.
   Arg: the context map from `reconstruct-context`. Returns boolean."
  core/paused?)

(def extract-completed-dag-tasks
  "Task IDs that finished successfully as `:dag/task-completed` events.
   Arg: a sequence of events. Returns a set of task-id values."
  core/extract-completed-dag-tasks)

(def extract-completed-dag-artifacts
  "Artifacts recorded on successful `:dag/task-completed` events.
   Arg: a sequence of events. Returns a vector of artifact maps
   (empty when none)."
  core/extract-completed-dag-artifacts)

(def extract-workspace-checkpoints
  "Workspace persistence records emitted at phase boundaries, used so a
   failed repair loop can resume from the last persisted branch/bundle
   rather than the spec base. Arg: a sequence of events. Returns a vector
   of checkpoint maps {:branch :bundle-path :commit-sha :persist-tier
   :env-id :phase :timestamp}; only records with a :branch and at least
   one of :bundle-path/:commit-sha are kept (empty when none)."
  core/extract-workspace-checkpoints)

(def extract-dag-pause-info
  "Completed task IDs + pause reason from the last `:dag/paused` event.
   Arg: a sequence of events. Returns {:completed-task-ids set
   :pause-reason keyword-or-nil}, or nil when no pause event exists."
  core/extract-dag-pause-info)

(def extract-completed-phases
  "Phase names (keywords) that completed — `:success` or `:skipped` outcome
   (see `core/completed-outcomes`). Arg: a sequence of events. Returns a vector
   of phase keywords in event order."
  core/extract-completed-phases)

(def extract-phase-results
  "Per-phase outcome/duration/timestamp from `:workflow/phase-completed`
   events. Arg: a sequence of events. Returns a map from phase keyword to
   {:outcome :duration-ms :timestamp} (empty when none)."
  core/extract-phase-results)

(def find-workflow-spec
  "The workflow spec recorded on the first `:workflow/started` event.
   Arg: a sequence of events. Returns the spec value, or nil if the run
   never emitted one."
  core/find-workflow-spec)

;------------------------------------------------------------------------------ Layer 1
;; High-level APIs

(def reconstruct-context
  "Build a complete resume-context map from a workflow id's events.

   Args:
   - `events-dir`  — base directory (e.g. `~/.miniforge/events`)
   - `workflow-id` — UUID string of the original run

   Returns a map: {:phase-results map :completed-phases vector-of-keywords
   :workflow-spec :workflow-id :completed? bool :failed? bool
   :event-count int :completed-dag-tasks set :completed-dag-artifacts vector
   :workspace-checkpoint :dag-paused? bool :dag-pause-reason keyword-or-nil
   :machine-snapshot :checkpoint-manifest}.

   Returns a canonical anomaly when no valid events exist for the
   workflow or input args are invalid. Events failing shape validation
   (missing/non-keyword `:event/type`) are dropped silently."
  core/reconstruct-context)

(def trim-pipeline
  "Drop the already-completed leading phases from a workflow's pipeline.

   Args: a workflow map with `:workflow/pipeline` and a collection of
   completed phase keywords. Returns the workflow with only the leading
   completed-phase prefix removed (completed phases after the first
   incomplete phase are preserved), or an anomaly on invalid input."
  core/trim-pipeline)

(def resolve-workflow-identity
  "Resolve `{:workflow-type keyword :workflow-version string}` for a
   resume run.

   Args:
   - `reconstructed` — context map from `reconstruct-context`
   - `fallback-fn`   — 0-arity returning a type keyword or nil

   Preference for type: keyword-valid id from the recorded spec, then the
   machine-snapshot `:execution/workflow-id` (unless a synthetic DAG-task
   key), then `fallback-fn`. Returns a canonical anomaly if no source
   yields a loadable type or input args are invalid."
  core/resolve-workflow-identity)
