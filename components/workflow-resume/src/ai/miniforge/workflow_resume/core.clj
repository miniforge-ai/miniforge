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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.interface.checkpoints :as workflow-checkpoints]
   [ai.miniforge.workflow-resume.schema :as schema]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Pure extractors over an event sequence

(defn completed?
  [reconstructed]
  (true? (:completed? reconstructed)))

(defn failed?
  [reconstructed]
  (true? (:failed? reconstructed)))

(defn paused?
  [reconstructed]
  (true? (:dag-paused? reconstructed)))

(defn extract-completed-dag-tasks
  [events]
  (->> events
       (filter #(= :dag/task-completed (:event/type %)))
       (map :dag/task-id)
       set))

(defn extract-completed-dag-artifacts
  [events]
  (->> events
       (filter #(= :dag/task-completed (:event/type %)))
       (mapcat #(get-in % [:dag/result :data :artifacts] []))
       vec))

(defn extract-workspace-checkpoints
  "Workspace persistence records emitted at phase boundaries.

   These are the container/worktree provenance records that survive when
   a DAG task eventually fails. Completed-DAG artifacts only exist for
   successful tasks; failed repair loops still need the latest persisted
   branch or bundle so resume continues from the last real workspace
   state instead of starting from the spec base again."
  [events]
  (->> events
       (filter #(= :workspace/persisted (:event/type %)))
       (keep (fn [event]
               (let [checkpoint {:branch (:workspace/branch event)
                                 :bundle-path (:workspace/bundle-path event)
                                 :commit-sha (:workspace/commit-sha event)
                                 :persist-tier (:workspace/persist-tier event)
                                 :env-id (:workspace/env-id event)
                                 :phase (:workflow/phase event)
                                 :timestamp (:event/timestamp event)}]
                 (when (and (:branch checkpoint)
                            (or (:bundle-path checkpoint)
                                (:commit-sha checkpoint)))
                   checkpoint))))
       vec))

(defn extract-dag-pause-info
  [events]
  (when-let [pause-event (->> events
                              (filter #(= :dag/paused (:event/type %)))
                              last)]
    {:completed-task-ids (set (:dag/completed-task-ids pause-event))
     :pause-reason (:dag/pause-reason pause-event)}))

(def completed-outcomes
  "Phase outcomes that count as completed for resume — the phase finished its
   work, so resume trims it from the pipeline rather than re-running it.
   :success did the work; :skipped short-circuited because the work was already
   done. :failure re-runs (it is excluded); :blocked and :redirected have no
   producer yet, so they too fall outside this set and would re-run."
  #{:success :skipped})

(defn extract-completed-phases
  [events]
  (->> events
       (filter #(= :workflow/phase-completed (:event/type %)))
       (filter #(contains? completed-outcomes (:phase/outcome %)))
       (mapv :workflow/phase)))

(defn extract-phase-results
  [events]
  (->> events
       (filter #(= :workflow/phase-completed (:event/type %)))
       (reduce (fn [acc evt]
                 (assoc acc (:workflow/phase evt)
                        ;; Reconstruct into the canonical phase-result shape so
                        ;; one accessor reads event-reconstructed and live
                        ;; checkpoint results alike — the review verdict at the
                        ;; one canonical location [:result :output :review/decision].
                        (cond-> {:outcome (:phase/outcome evt)
                                 :duration-ms (:phase/duration-ms evt)
                                 :timestamp (:event/timestamp evt)}
                          (:phase/review-decision evt)
                          (assoc-in [:result :output :review/decision]
                                    (:phase/review-decision evt)))))
               {})))

(defn find-workflow-spec
  [events]
  (->> events
       (filter #(= :workflow/started (:event/type %)))
       first
       :workflow/spec))

(defn- ensure-reconstruction-source
  [checkpoint-data events-dir workflow-id raw-events]
  (when-not (or checkpoint-data (seq raw-events))
    (anomaly/anomaly :not-found
                     (str "No events found for workflow: " workflow-id)
                     {:workflow-id workflow-id
                      :events-dir (str events-dir)
                      :raw-event-count (count raw-events)})))

(defn- checkpoint-status
  [checkpoint-data]
  (get-in checkpoint-data [:machine-snapshot :execution/status]))

(defn- checkpoint-dag-result
  [checkpoint-data]
  (get-in checkpoint-data [:machine-snapshot :execution/dag-result]))

(defn- reconstructed-completed?
  [by-type checkpoint-data]
  (let [status (checkpoint-status checkpoint-data)]
    (if (some? status)
      (contains? #{:completed :completed-with-warnings} status)
      (boolean (seq (get by-type :workflow/completed))))))

(defn- reconstructed-failed?
  [by-type checkpoint-data]
  (let [status (checkpoint-status checkpoint-data)]
    (if (some? status)
      (= :failed status)
      (boolean (seq (get by-type :workflow/failed))))))

(def ^:private resume-config-resource
  "config/workflow-resume/resume.edn")

(defn- read-resume-config
  []
  (if-let [resource (io/resource resume-config-resource)]
    (:workflow-resume/resume (edn/read-string (slurp resource)))
    (anomaly/anomaly :not-found
                     "Workflow resume config resource not found"
                     {:resource resume-config-resource
                      :config/error :invalid-config})))

(def ^:private resume-config
  (delay (read-resume-config)))

(defn- config-set
  [k]
  (set (get @resume-config k)))

(def completed-phase-statuses
  "Phase result statuses that are safe to skip on resume."
  (config-set :completed-phase-statuses))

(def blocking-review-decisions
  "Review decisions that must resume the repair path."
  (config-set :blocking-review-decisions))

(defn- phase-result-status
  [phase-result]
  (or (:status phase-result)
      (:phase/status phase-result)
      (:outcome phase-result)
      (:phase/outcome phase-result)
      (get-in phase-result [:result :status])
      (get-in phase-result [:phase/result :status])))

(defn- review-blocked?
  [phase-id phase-result]
  (and (= :review phase-id)
       (contains? blocking-review-decisions
                  (response/review-decision (response/phase-output phase-result)))))

(defn- completed-phase-result?
  [phase-id phase-result]
  (and phase-result
       (not (review-blocked? phase-id phase-result))
       (contains? completed-phase-statuses
                  (phase-result-status phase-result))))

(defn- checkpoint-phase-order
  [checkpoint-data phase-results]
  (let [manifest-order (get-in checkpoint-data [:manifest :workflow/phases-completed])]
    (vec (concat manifest-order
                 (remove (set manifest-order) (keys phase-results))))))

(defn- event-phase-order
  [events]
  (->> events
       (filter #(= :workflow/phase-completed (:event/type %)))
       (map :workflow/phase)
       distinct
       vec))

(defn- completed-checkpoint-phases
  [checkpoint-data phase-results]
  (->> (checkpoint-phase-order checkpoint-data phase-results)
       (filter #(completed-phase-result? % (get phase-results %)))))

(defn- completed-event-phases
  [events]
  (let [event-results (extract-phase-results events)]
    (->> (event-phase-order events)
         (filter #(completed-phase-result? % (get event-results %))))))

(defn- restored-completed-phases
  [checkpoint-data events phase-results]
  (->> (concat (completed-event-phases events)
               (completed-checkpoint-phases checkpoint-data phase-results))
       distinct
       vec))

(defn- restored-phase-results
  [checkpoint-data events]
  (if checkpoint-data
    (:phase-results checkpoint-data)
    (extract-phase-results events)))

(defn- restored-dag-pause-info
  [checkpoint-data events]
  (let [dag-result (checkpoint-dag-result checkpoint-data)]
    (or (when (:paused? dag-result)
          {:completed-task-ids (set (:completed-task-ids dag-result))
           :pause-reason (:pause-reason dag-result)})
        (extract-dag-pause-info events))))

(defn- restored-completed-dag-tasks
  [checkpoint-data events]
  (let [dag-result (checkpoint-dag-result checkpoint-data)
        checkpoint-task-ids (set (:completed-task-ids dag-result))
        event-task-ids (extract-completed-dag-tasks events)
        pause-task-ids (:completed-task-ids (restored-dag-pause-info checkpoint-data events))]
    (or (not-empty checkpoint-task-ids)
        (not-empty event-task-ids)
        pause-task-ids
        #{})))

(defn- restored-completed-dag-artifacts
  [checkpoint-data events]
  (let [dag-result (checkpoint-dag-result checkpoint-data)
        checkpoint-artifacts (vec (get dag-result :artifacts []))
        event-artifacts (extract-completed-dag-artifacts events)]
    (if (seq checkpoint-artifacts)
      checkpoint-artifacts
      event-artifacts)))

(defn- restored-workspace-checkpoint
  [events]
  (last (extract-workspace-checkpoints events)))

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
     :completed-dag-artifacts — recovered artifacts from completed DAG tasks
     :dag-paused?          — boolean
     :dag-pause-reason     — keyword or nil

   Returns a `:not-found` anomaly if no valid events exist for the
   workflow. Events that fail shape validation (missing or non-keyword
   `:event/type`) are silently dropped at the boundary."
  [events-dir workflow-id]
  (anomaly/let-ok [_valid (schema/validate! schema/ReconstructContextInput
                                            {:events-dir events-dir
                                             :workflow-id workflow-id}
                                            {:message "Invalid reconstruct-context input"
                                             :schema-name :workflow-resume/reconstruct-context})]
    (let [checkpoint-data (workflow-checkpoints/load-checkpoint-data workflow-id)
          raw-events (or (es/read-workflow-events-by-id events-dir workflow-id) [])
          events (vec (filter schema/valid-event? raw-events))]
      (anomaly/let-ok [_source (ensure-reconstruction-source checkpoint-data
                                                             events-dir
                                                             workflow-id
                                                             raw-events)]
        (let [by-type (group-by :event/type events)
              phase-results (restored-phase-results checkpoint-data events)
              completed-phases (restored-completed-phases checkpoint-data events phase-results)
              workflow-spec (find-workflow-spec events)
              started-event (first (get by-type :workflow/started))
              machine-snapshot (:machine-snapshot checkpoint-data)
              completed? (reconstructed-completed? by-type checkpoint-data)
              failed? (reconstructed-failed? by-type checkpoint-data)
              completed-dag-tasks (restored-completed-dag-tasks checkpoint-data events)
              completed-dag-artifacts (restored-completed-dag-artifacts checkpoint-data events)
              workspace-checkpoint (restored-workspace-checkpoint events)
              dag-pause-info (restored-dag-pause-info checkpoint-data events)]
          {:phase-results phase-results
           :completed-phases completed-phases
           :workflow-spec workflow-spec
           :workflow-id (or (:execution/id machine-snapshot)
                            (:workflow/id started-event)
                            workflow-id)
           :completed? completed?
           :failed? failed?
           :event-count (count events)
           :completed-dag-tasks completed-dag-tasks
           :completed-dag-artifacts completed-dag-artifacts
           :workspace-checkpoint workspace-checkpoint
           :dag-paused? (boolean dag-pause-info)
           :dag-pause-reason (:pause-reason dag-pause-info)
           :machine-snapshot machine-snapshot
           :checkpoint-manifest (:manifest checkpoint-data)})))))

;------------------------------------------------------------------------------ Layer 1
;; Pipeline trimming

(defn trim-pipeline
  "Drop the already-completed prefix from a workflow's pipeline.

   Pure: takes a workflow map with `:workflow/pipeline` and a
   collection of completed phase keywords; returns the workflow with only
   leading completed phases removed. Completed phases after the first
   incomplete phase are preserved."
  [workflow completed-phases]
  (anomaly/let-ok [_valid (schema/validate! schema/TrimPipelineInput
                                            {:workflow workflow
                                             :completed-phases completed-phases}
                                            {:message "Invalid trim-pipeline input"
                                             :schema-name :workflow-resume/trim-pipeline})]
    (let [completed-set (set completed-phases)
          remaining (vec (drop-while #(completed-set (:phase %))
                                     (get workflow :workflow/pipeline [])))]
      (assoc workflow :workflow/pipeline remaining))))

;------------------------------------------------------------------------------ Layer 1
;; Identity resolution

(defn- synthetic-dag-task-workflow-id?
  "True when the recorded workflow id is an internal DAG task workflow key,
   not a loadable top-level workflow type from the registry."
  [workflow-id]
  (and (keyword? workflow-id)
       (str/starts-with? (name workflow-id) "dag-task-")))

(def ^:private workflow-type-identifier-re
  "Regex for an unqualified workflow-type keyword name. Workflow types are loader keys
   like :canonical-sdlc / :quick-fix — strict identifier characters only.
   This rejects values like \"In-flight PR / branch / task-claim registry\"
   (a human title that the producer side accidentally records under
   `:name`) before they get keywordized into an unloadable lookup key."
  #"^[A-Za-z][A-Za-z0-9._+!?*<>=-]*$")

(defn- valid-workflow-type-keyword?
  "True when `v` is an unqualified workflow-type keyword name."
  [v]
  (and (keyword? v)
       (nil? (namespace v))
       (re-matches workflow-type-identifier-re (name v))))

(defn- candidate-workflow-type
  "Pull a candidate workflow-type keyword out of the recorded workflow
   spec. Preference order:

   1. `:workflow-type` — canonical key when callers thread it through
   2. `:workflow/id`    — same shape as a workflow-config map
   3. `:name`           — legacy / TUI shape; ONLY accepted when the
      value is an unqualified workflow-type keyword/name matching a
      strict keyword-name regex, because some producers
      (notably the cli + TUI persistence path) record the human spec
      title under `:name` and a title with spaces / slashes would
      keywordize into an unloadable key.

   Returns a keyword or nil."
  [workflow-spec]
  (let [keyword-if-valid (fn [v]
                           (cond
                             (valid-workflow-type-keyword? v) v
                             (and (string? v)
                                  (re-matches workflow-type-identifier-re v))
                             (keyword v)
                             :else nil))]
    (or (keyword-if-valid (get workflow-spec :workflow-type))
        (keyword-if-valid (get workflow-spec :workflow/id))
        (keyword-if-valid (get workflow-spec :name)))))

(defn resolve-workflow-identity
  "Resolve `{:workflow-type :workflow-version}` for a resume run.

   Preference order for `:workflow-type`:

   1. A keyword-valid identifier extracted from the recorded
      `:workflow/spec` via `candidate-workflow-type` (tries
      `:workflow-type`, `:workflow/id`, then `:name`).
   2. The `:execution/workflow-id` from the machine snapshot, unless
      it's a synthetic DAG-task key.
   3. The caller-supplied `fallback-fn` (typically a selection profile).

   Returns a `:not-found` anomaly if no source yields a loadable type.

   Arguments:
   - `reconstructed` — context map from `reconstruct-context`
   - `fallback-fn`   — 0-arity; returns a type keyword or nil

   Pre-2026-05-23: this fn did `(some-> workflow-spec :name keyword)`
   unconditionally, which produced unloadable keywords like
   `:In-flight PR / branch / task-claim registry` whenever a producer
   recorded the spec title under `:name` (observed dogfooding
   work/in-flight-pr-registry.spec.edn, workflow a92b2c97). The
   identifier-regex gate above keeps the legacy path working for
   well-formed names while rejecting human-title strings."
  [reconstructed fallback-fn]
  (anomaly/let-ok [_valid (schema/validate! schema/ResolveWorkflowIdentityInput
                                            {:reconstructed reconstructed
                                             :fallback-fn fallback-fn}
                                            {:message "Invalid resolve-workflow-identity input"
                                             :schema-name :workflow-resume/resolve-workflow-identity})]
    (let [workflow-spec (:workflow-spec reconstructed)
          machine-snapshot (:machine-snapshot reconstructed)
          workflow-id-from-snapshot (:execution/workflow-id machine-snapshot)
          workflow-type (or (candidate-workflow-type workflow-spec)
                            (when-not (synthetic-dag-task-workflow-id? workflow-id-from-snapshot)
                              workflow-id-from-snapshot)
                            (fallback-fn))
          workflow-version (or (get workflow-spec :version)
                               (:execution/workflow-version machine-snapshot)
                               "latest")]
      (if workflow-type
        {:workflow-type workflow-type
         :workflow-version workflow-version}
        (anomaly/anomaly :not-found
                         "Could not resolve a workflow type for resume"
                         {:operation :resume-workflow
                          :workflow-spec workflow-spec})))))
