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

(ns ai.miniforge.workflow-resume.core
  "Pure reconstruction of execution context from recorded event streams.

   This is the domain core of workflow resume: given a workflow id, it
   reads the event history (via event-stream/reader), extracts the set
   of phases + DAG tasks that have already completed, and builds the
   trimmed workflow and pre-completed task set the workflow runner
   needs in order to pick up where the original run left off.

   Zero I/O beyond the event-stream reader. Zero display. Zero runtime
   wiring. Adapters (CLI, HTTP API, dashboard) compose those on top.

   Validation boundary: public API fns (`reconstruct-context`,
   `trim-pipeline`, `resolve-workflow-identity`) validate their inputs
   via `schema/validate!` before the pure core runs. Events read from
   disk are filtered with `schema/valid-event?` — events without a
   keyword `:event/type` are dropped at the boundary, so everything
   the extractors see is well-shaped."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow-resume.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Pure extractors over an event sequence

(defn extract-completed-dag-tasks
  "Task IDs that finished successfully as `:dag/task-completed` events."
  [events]
  (->> events
       (filter #(= :dag/task-completed (:event/type %)))
       (map :dag/task-id)
       set))

(defn extract-dag-pause-info
  "Find the last `:dag/paused` event and extract completed task IDs + reason."
  [events]
  (when-let [pause-event (->> events
                              (filter #(= :dag/paused (:event/type %)))
                              last)]
    {:completed-task-ids (set (:dag/completed-task-ids pause-event))
     :pause-reason (:dag/pause-reason pause-event)}))

(defn extract-completed-phases
  "Phase names (keywords) that completed with `:success` outcome."
  [events]
  (->> events
       (filter #(= :workflow/phase-completed (:event/type %)))
       (filter #(= :success (:phase/outcome %)))
       (mapv :workflow/phase)))

(defn extract-phase-results
  "Map from phase keyword to outcome/duration/timestamp, built from
   `:workflow/phase-completed` events."
  [events]
  (->> events
       (filter #(= :workflow/phase-completed (:event/type %)))
       (reduce (fn [acc evt]
                 (assoc acc (:workflow/phase evt)
                        {:outcome (:phase/outcome evt)
                         :duration-ms (:phase/duration-ms evt)
                         :timestamp (:event/timestamp evt)}))
               {})))

(defn find-workflow-spec
  "The workflow spec recorded on the first `:workflow/started` event,
   or nil if the run never emitted one."
  [events]
  (->> events
       (filter #(= :workflow/started (:event/type %)))
       first
       :workflow/spec))

;------------------------------------------------------------------------------ Layer 1
;; Context reconstruction

(defn reconstruct-context
  "Build a complete resume-context map from a workflow id's events.

   Arguments:
   - `events-dir`  — base directory (e.g. `~/.miniforge/events`)
   - `workflow-id` — UUID string of the original run

   Returns a map with:
     :phase-results        — {phase → {:outcome :duration-ms :timestamp}}
     :completed-phases     — vector of phase keywords in order
     :workflow-spec        — original spec from :workflow/started
     :workflow-id          — canonical id (either from event or the arg)
     :completed?           — true if :workflow/completed emitted
     :failed?              — true if :workflow/failed emitted
     :event-count          — int (count of events that passed shape validation)
     :completed-dag-tasks  — set of DAG task IDs that succeeded
     :dag-paused?          — boolean
     :dag-pause-reason     — keyword or nil

   Throws `:anomalies/not-found` if no valid events exist for the
   workflow — adapters translate that to a user-facing error. Events
   that fail shape validation (missing or non-keyword `:event/type`)
   are silently dropped at the boundary."
  [events-dir workflow-id]
  (schema/validate! schema/ReconstructContextInput
                    {:events-dir events-dir :workflow-id workflow-id}
                    {:message "Invalid reconstruct-context input"})
  (let [raw-events (es/read-workflow-events-by-id events-dir workflow-id)
        events (vec (filter schema/valid-event? raw-events))
        _ (when-not (seq events)
            (response/throw-anomaly! :anomalies/not-found
                                    (str "No events found for workflow: " workflow-id)
                                    {:workflow-id workflow-id
                                     :events-dir (str events-dir)
                                     :raw-event-count (count raw-events)}))
        by-type (group-by :event/type events)
        completed-phases (extract-completed-phases events)
        phase-results (extract-phase-results events)
        workflow-spec (find-workflow-spec events)
        started-event (first (get by-type :workflow/started))
        completed? (boolean (seq (get by-type :workflow/completed)))
        failed? (boolean (seq (get by-type :workflow/failed)))
        completed-dag-tasks (extract-completed-dag-tasks events)
        dag-pause-info (extract-dag-pause-info events)]
    {:phase-results phase-results
     :completed-phases completed-phases
     :workflow-spec workflow-spec
     :workflow-id (or (:workflow/id started-event) workflow-id)
     :completed? completed?
     :failed? failed?
     :event-count (count events)
     :completed-dag-tasks (or (not-empty completed-dag-tasks)
                              (:completed-task-ids dag-pause-info)
                              #{})
     :dag-paused? (boolean dag-pause-info)
     :dag-pause-reason (:pause-reason dag-pause-info)}))

;------------------------------------------------------------------------------ Layer 1
;; Pipeline trimming

(defn trim-pipeline
  "Drop already-completed phases from a workflow's pipeline.

   Pure: takes a workflow map with `:workflow/pipeline` and a
   collection of completed phase keywords; returns the workflow with
   its pipeline reduced to only the remaining phases."
  [workflow completed-phases]
  (schema/validate! schema/TrimPipelineInput
                    {:workflow workflow :completed-phases completed-phases}
                    {:message "Invalid trim-pipeline input"})
  (let [completed-set (set completed-phases)
        remaining (vec (remove #(completed-set (:phase %))
                               (get workflow :workflow/pipeline [])))]
    (assoc workflow :workflow/pipeline remaining)))

;------------------------------------------------------------------------------ Layer 1
;; Identity resolution

(defn resolve-workflow-identity
  "Resolve `{:workflow-type :workflow-version}` for a resume run.

   Recorded workflow spec wins when present. Otherwise delegates to a
   caller-supplied fallback-fn (typically reads a selection profile).
   Throws `:anomalies/not-found` if neither source yields a type.

   Arguments:
   - `reconstructed` — context map from `reconstruct-context`
   - `fallback-fn`   — 0-arity; returns a type keyword or nil"
  [reconstructed fallback-fn]
  (schema/validate! schema/ResolveWorkflowIdentityInput
                    {:reconstructed reconstructed :fallback-fn fallback-fn}
                    {:message "Invalid resolve-workflow-identity input"})
  (let [workflow-spec (:workflow-spec reconstructed)
        workflow-type (or (some-> workflow-spec :name keyword)
                          (fallback-fn))
        workflow-version (get workflow-spec :version "latest")]
    (when-not workflow-type
      (response/throw-anomaly! :anomalies/not-found
                              "Could not resolve a workflow type for resume"
                              {:operation :resume-workflow
                               :workflow-spec workflow-spec}))
    {:workflow-type workflow-type
     :workflow-version workflow-version}))
