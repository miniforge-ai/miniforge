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

(ns ai.miniforge.workflow.execution
  "Phase execution lifecycle.

   Handles execution of individual phase steps through the enter -> gates -> leave lifecycle.
   Processes results, tracks metrics/files/artifacts, and determines phase transitions."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
            [ai.miniforge.workflow.fsm :as workflow-fsm]))

;------------------------------------------------------------------------------ Layer 0: Atomic operations

(defn execute-enter
  "Execute the :enter function of an interceptor.

   Returns updated context."
  [interceptor ctx]
  (let [phase-name (get-in interceptor [:config :phase])]
    (if-let [enter-fn (:enter interceptor)]
      (try
        (enter-fn ctx)
        (catch Exception ex
          (if-let [error-fn (:error interceptor)]
            (error-fn ctx ex)
            (let [anom (response/from-exception ex)]
              (-> ctx
                  (context/transition-to-failed)
                  (update :execution/errors conj
                          {:type :phase-error
                           :phase phase-name
                           :message (ex-message ex)
                           :data (ex-data ex)
                           :anomaly anom})
                  (update :execution/response-chain
                          response/add-failure phase-name
                          (assoc anom :anomaly/category :anomalies.phase/enter-failed
                                      :anomaly/phase phase-name)
                          {:error (ex-message ex)
                           :data (ex-data ex)}))))))
      ctx)))

(defn execute-leave
  "Execute the :leave function of an interceptor.

   Returns updated context."
  [interceptor ctx]
  (let [phase-name (get-in interceptor [:config :phase])]
    (if-let [leave-fn (:leave interceptor)]
      (try
        (leave-fn ctx)
        (catch Exception ex
          (let [anom (response/from-exception ex)]
            (-> ctx
                (update :execution/errors conj
                        {:type :leave-error
                         :phase phase-name
                         :message (ex-message ex)
                         :anomaly anom})
                (update :execution/response-chain
                        response/add-failure phase-name
                        (assoc anom :anomaly/category :anomalies.phase/leave-failed
                                    :anomaly/phase phase-name)
                        {:error (ex-message ex)})))))
      ctx)))

(defn extract-phase-result
  "Extract phase result from context."
  [ctx]
  (get-in ctx [:phase]))

(defn already-done?
  "Check if phase result indicates work was already done."
  [phase-result]
  (or (phase/already-done? phase-result)
      (phase/already-done? (:result phase-result))))

(defn apply-gate-validation
  "Apply gate validation to phase result.

   Returns updated phase-result with :phase/status and :phase/gate-errors if gates fail.
   Skips gate checks when phase indicates work is already done."
  [interceptor phase-result ctx]
  (if (already-done? phase-result)
    phase-result
    (let [gate-keywords (get-in interceptor [:config :gates] [])
          artifact (or (:artifact phase-result)
                       (get-in phase-result [:result :artifact])
                       (get-in phase-result [:result :output]))]
      (if (and (seq gate-keywords) artifact)
        (let [gate-result (gate/check-gates gate-keywords artifact ctx)]
          (if (:passed? gate-result)
            phase-result
            (assoc phase-result
                   :phase/status :failed
                   :phase/gate-errors (:failed-gates gate-result))))
        phase-result))))

(defn phase-succeeded?
  "Check if phase completed successfully or work was already done."
  [phase-result]
  (or (phase/succeeded-or-done? phase-result)
      (already-done? phase-result)))

(defn update-response-chain
  "Update response chain with phase result."
  [ctx phase-name phase-result]
  (if (phase-succeeded? phase-result)
    (update ctx :execution/response-chain
            response/add-success phase-name phase-result)
    (let [gate-errors (:phase/gate-errors phase-result)
          anomaly (if gate-errors
                    (response/gate-anomaly
                     :anomalies.gate/validation-failed
                     (str "Gate validation failed for phase " (name phase-name))
                     gate-errors
                     {:anomaly/phase phase-name})
                    (response/make-anomaly
                     :anomalies.phase/agent-failed
                     (str "Agent failed in phase " (name phase-name))
                     {:anomaly/phase phase-name}))]
      (update ctx :execution/response-chain
              response/add-failure phase-name
              anomaly
              phase-result))))

(defn record-phase-metrics
  "Record phase metrics in execution context."
  [ctx phase-result merge-metrics-fn]
  (update ctx :execution/metrics merge-metrics-fn
          (get phase-result :metrics {})))

(defn track-phase-files
  "Track files written by phase for meta-agent monitoring.

   In the new environment model, code changes live in the execution
   environment's git working tree (:execution/worktree-path) rather than
   being serialized into phase results. File tracking via :code/files in
   phase output is therefore a no-op; actual file discovery happens at
   release time via git diff."
  [ctx _phase-result]
  ;; Phase results no longer carry :code/files.
  ;; File changes are in the environment's worktree, captured at release time.
  ctx)

(defn record-phase-artifacts
  "Record phase artifacts in execution context.

   In the new environment model, phase results carry provenance metadata
   (:environment-id, :summary, :metrics) rather than serialized :code/files.
   The recorded artifact captures lightweight provenance metadata for the
   evidence bundle."
  [ctx phase-result]
  (let [result   (get phase-result :result)
        artifact (when (map? result)
                   (not-empty (select-keys result [:status :environment-id
                                                   :summary :metrics])))]
    (update ctx :execution/artifacts into (if artifact [artifact] []))))

;------------------------------------------------------------------------------ Layer 1: Composition

(defn execute-phase-lifecycle
  "Execute phase enter -> gates -> leave lifecycle.

   Returns [ctx phase-result]."
  [interceptor ctx]
  ;; Clear :phase map before each phase to prevent stale state (e.g. :redirect-to)
  ;; from leaking across phase boundaries
  (let [ctx-clean (dissoc ctx :phase)
        ctx-entered (execute-enter interceptor ctx-clean)
        phase-result (extract-phase-result ctx-entered)
        phase-result-gated (apply-gate-validation interceptor phase-result ctx-entered)
        ctx-left (execute-leave interceptor (assoc ctx-entered :phase phase-result-gated))]
    [ctx-left (extract-phase-result ctx-left)]))

(defn process-phase-result
  "Process phase result: update response chain, record metrics/files/artifacts.

   Returns updated context."
  [ctx phase-name phase-result merge-metrics-fn]
  (-> ctx
      (update-response-chain phase-name phase-result)
      (assoc-in [:execution/phase-results phase-name] phase-result)
      (record-phase-metrics phase-result merge-metrics-fn)
      (record-phase-artifacts phase-result)
      (track-phase-files phase-result)))

(defn determine-phase-event
  "Translate a phase result into an execution-machine event."
  [_phase-config phase-result]
  (let [redirect-to (:redirect-to phase-result)]
    (cond
      (phase/retrying? phase-result)
      :phase/retry

      (phase/already-done? phase-result)
      :phase/already-done

      (phase/succeeded? phase-result)
      :phase/succeed

      (and (phase/failed? phase-result) redirect-to)
      (workflow-fsm/redirect-event redirect-to)

      (phase/failed? phase-result)
      :phase/fail

      :else
      :phase/succeed)))

(def max-redirects
  "Maximum number of phase redirects before failing to prevent infinite loops."
  5)

(defn apply-phase-transition
  "Apply a phase-outcome event through the execution machine.

   Returns updated context with refreshed machine projections or terminal failure."
  [ctx event _pipeline _transition-to-completed-fn transition-to-failed-fn]
  (let [redirect-count (get ctx :execution/redirect-count 0)
        is-redirect? (= "workflow.event" (namespace event))]
    (cond
      ;; Redirect cycle limit exceeded
      (and is-redirect? (>= redirect-count max-redirects))
      (let [anom (response/make-anomaly
                   :anomalies.workflow/max-redirects-exceeded
                   (str "Redirect cycle limit exceeded (" max-redirects " redirects)"))]
        (-> ctx
            (update :execution/errors conj
                    {:type :max-redirects-exceeded
                     :message (str "Exceeded " max-redirects " redirects")
                     :anomaly anom})
            (update :execution/response-chain
                    response/add-failure :pipeline anom
                    {:redirect-count redirect-count})
            (transition-to-failed-fn)))

      :else
      (let [prior-state (:execution/fsm-state ctx)
            next-ctx (context/transition-execution ctx event)
            state-changed? (not= prior-state (:execution/fsm-state next-ctx))]
        (if (or state-changed? (= :phase/retry event))
          next-ctx
          (let [anom (response/make-anomaly
                       :anomalies.workflow/invalid-transition
                       (str "Invalid phase transition event: " event))]
            (-> ctx
                (update :execution/errors conj
                        {:type :invalid-transition
                         :message (str "Invalid phase transition event: " event)
                         :anomaly anom})
                (update :execution/response-chain
                        response/add-failure :pipeline
                        anom
                        {:error (str "Invalid phase transition event: " event)
                         :event event})
                (transition-to-failed-fn))))))))

;------------------------------------------------------------------------------ Layer 1.5: DAG integration helpers

(defn extract-plan-from-phase-result
  "Extract a plan map from an interceptor-style phase result, if present."
  [phase-result]
  (let [output (get-in phase-result [:result :output])]
    (when (and (map? output) (:plan/id output))
      output)))

(defn index-after-phase
  "Find the index of the phase immediately after the named phase in the pipeline."
  [pipeline phase-kw]
  (some (fn [[i ic]]
          (when (= phase-kw (get-in ic [:config :phase]))
            (inc i)))
        (map-indexed vector pipeline)))

(defn dag-skip-reason
  "Return the reason DAG execution should be skipped, or nil if it should proceed.
   Reasons are keywords — :not-plan-phase, :disabled, :no-plan-id, :no-tasks."
  [phase-name phase-result ctx]
  (cond
    (not= :plan phase-name) :not-plan-phase
    (:disable-dag-execution ctx) :disabled
    :else (let [plan (extract-plan-from-phase-result phase-result)]
            (cond
              (nil? plan) :no-plan-id
              (empty? (:plan/tasks plan)) :no-tasks
              :else nil))))

(defn- classify-output
  "Describe the shape of the :output value without leaking its contents.
   Returns a keyword suitable for event payload."
  [output]
  (cond
    (nil? output) :nil
    (map? output) :map
    (sequential? output) :sequential
    (string? output) :string
    :else :other))

(def ^:private failure-statuses
  #{:error :failed :failure})

(defn- failed?
  "True when a result map carries a failure status."
  [result]
  (contains? failure-statuses (:status result)))

(defn- summarize-error
  "Keys-only/trimmed summary of a failure result's :error map so the event
   carries enough to diagnose without leaking large payloads (stack traces,
   token arrays). Returns nil when the result isn't a failure or carries no
   diagnosable error fields.

   Reads the canonical response-shape error field (:message) and emits the
   canonical event-schema field (:error/message). Any producer using a
   non-canonical shape MUST convert at its boundary — this fn is inside
   the workflow runtime and does not coerce."
  [result]
  (when (failed? result)
    (let [err (:error result)
          msg (:message err)
          data-keys (some-> err :data keys sort)
          summary (cond-> {}
                    msg                       (assoc :error/message
                                                     (subs (str msg) 0 (min 500 (count (str msg)))))
                    (keyword? (:anomaly err)) (assoc :anomaly (:anomaly err))
                    (seq data-keys)           (assoc :error/data-keys (vec data-keys)))]
      (not-empty summary))))

(defn dag-skip-diagnostic
  "Produce a structured snapshot of phase-result for :no-plan-id / :no-tasks
   skips. Keys-only (no values) so the event stays bounded and we don't leak
   full plan content into the log.

   Returned shape:
     {:phase-result/keys [...]
      :result/keys       [...]
      :result/status     <value or nil>
      :output/type       :nil | :map | :sequential | :string | :other
      :output/keys       [...] (only if :map)
      :output/has-plan-id? bool (only if :map)
      :plan/task-count   int (only when reason is :no-tasks)
      :result/error      {:error/message ... :anomaly ... :error/data-keys ...}
                         (only when :result is a failure per summarize-error)}"
  [phase-result reason]
  (let [phase-keys    (some-> phase-result keys sort)
        result        (:result phase-result)
        result-keys   (some-> result keys sort)
        result-status (:status result)
        output        (:output result)
        output-type   (classify-output output)
        output-keys   (when (= :map output-type) (sort (keys output)))
        has-plan-id?  (when (= :map output-type) (boolean (:plan/id output)))
        plan          (extract-plan-from-phase-result phase-result)
        error-summary (summarize-error result)]
    (cond-> {:phase-result/keys (vec phase-keys)
             :result/keys       (vec result-keys)
             :result/status     result-status
             :output/type       output-type}
      (seq output-keys)       (assoc :output/keys (vec output-keys))
      (some? has-plan-id?)    (assoc :output/has-plan-id? has-plan-id?)
      error-summary           (assoc :result/error error-summary)
      (= :no-tasks reason)    (assoc :plan/task-count
                                     (count (:plan/tasks plan))))))

(defn dag-applicable?
  "Check whether DAG execution should be attempted for this phase result.
   Returns the plan map if applicable, nil otherwise."
  [phase-name phase-result ctx]
  (when (and (= :plan phase-name)
             (not (:disable-dag-execution ctx)))
    (extract-plan-from-phase-result phase-result)))

(defn- resolve-event-stream
  "Find the event stream from context, matching phase/telemetry's lookup."
  [ctx]
  (or (:event-stream ctx)
      (:execution/event-stream ctx)
      (get-in ctx [:execution/opts :event-stream])))

(defn- resolve-workflow-id
  [ctx]
  (or (:execution/id ctx) (:workflow/id ctx) (:workflow-id ctx)))

(defn- emit-dag-considered!
  "Emit a :workflow/dag-considered event describing whether DAG fired and why.
   Swallows errors — observability must not break execution."
  [ctx outcome reason extra]
  (when-let [stream (resolve-event-stream ctx)]
    (try
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)
            event (merge
                    {:event/type :workflow/dag-considered
                     :event/timestamp (str (java.time.Instant/now))
                     :workflow/id (resolve-workflow-id ctx)
                     :dag/outcome outcome
                     :dag/reason reason}
                    extra)]
        (publish! stream event))
      (catch Exception _ nil))))

(defn- merge-sub-worktree-changes!
  "Copy changed files from DAG sub-worktrees into the parent worktree.
   Each sub-workflow wrote to its own isolated worktree. For the release
   phase to find dirty files, we need to merge those changes back."
  [parent-worktree sub-worktree-paths]
  (doseq [sub-wt sub-worktree-paths]
    (try
      (let [{:keys [out]} (shell/sh
                            "git" "diff" "--name-only" "HEAD"
                            :dir sub-wt)
            changed-files (remove str/blank?
                                  (str/split-lines (or out "")))]
        (doseq [f changed-files]
          (let [src (io/file sub-wt f)
                dst (io/file parent-worktree f)]
            (when (.exists src)
              (io/make-parents dst)
              (io/copy src dst)))))
      (catch Exception _e nil))))

(defn apply-dag-success
  "Apply a successful DAG result to the execution context.
   Merges artifact provenance, copies sub-worktree file changes into the
   parent worktree, synthesizes an :implement phase result, and advances
   past implement to verify → review → release."
  [ctx dag-result pipeline transition-to-completed-fn transition-to-failed-fn]
  (let [artifacts  (:artifacts dag-result)
        task-count (count artifacts)
        ;; Merge sub-worktree changes into parent worktree so the release
        ;; phase can discover dirty files via git status.
        parent-wt  (or (get ctx :execution/worktree-path)
                       (System/getProperty "user.dir"))
        sub-wt-paths (:worktree-paths dag-result)
        _  (when (and parent-wt (seq sub-wt-paths))
             (merge-sub-worktree-changes! parent-wt sub-wt-paths))
        ;; Synthesize new-style implement phase result.
        synthesized-implement-result
        {:name   :implement
         :status :completed
         :result {:status         :success
                  :environment-id (get ctx :execution/environment-id)
                  :summary        (str "DAG executed " task-count " task(s) successfully")
                  :metrics        (merge {:task-count task-count}
                                         (:metrics dag-result))}}
        ctx-with-dag (-> ctx
                         (update :execution/artifacts into artifacts)
                         (assoc :execution/dag-result dag-result)
                         (assoc-in [:execution/phase-results :implement]
                                   synthesized-implement-result))
        post-impl-idx (index-after-phase pipeline :implement)
        next-phase (some-> (get pipeline post-impl-idx) :config :phase)]
    (if (and post-impl-idx (< post-impl-idx (count pipeline)))
      (apply-phase-transition ctx-with-dag
                              (workflow-fsm/redirect-event next-phase)
                              pipeline
                              transition-to-completed-fn
                              transition-to-failed-fn)
      (transition-to-completed-fn ctx-with-dag))))

(defn apply-dag-failure
  "Apply a failed DAG result to the execution context."
  [ctx dag-result transition-to-failed-fn]
  (transition-to-failed-fn
   (update ctx :execution/errors conj
           {:type :dag-execution-failed
            :dag-result dag-result})))

(defn try-dag-execution
  "After plan phase, execute all plans via the DAG executor.

   The DAG executor is the universal executor — it handles both parallel
   and sequential plans. Returns updated context with DAG results and
   skipped-to index, or nil if DAG execution is not applicable.

   Always emits :workflow/dag-considered so the event log captures whether
   DAG fired and — when skipped — exactly why."
  [ctx phase-name phase-result pipeline
   transition-to-completed-fn transition-to-failed-fn]
  (let [skip-reason (dag-skip-reason phase-name phase-result ctx)]
    (if skip-reason
      (do
        (when (= :plan phase-name)
          (let [base  {:phase/name phase-name}
                extra (if (contains? #{:no-plan-id :no-tasks} skip-reason)
                        (assoc base :dag/diagnostic
                               (dag-skip-diagnostic phase-result skip-reason))
                        base)]
            (emit-dag-considered! ctx :skipped skip-reason extra)))
        nil)
      (let [plan (extract-plan-from-phase-result phase-result)
            ctx-with-resume (assoc ctx :pre-completed-ids
                                   (get-in ctx [:execution/opts :pre-completed-dag-tasks] #{}))
            _ (emit-dag-considered! ctx :activated :plan-has-tasks
                                    {:plan/id (:plan/id plan)
                                     :plan/task-count (count (:plan/tasks plan))})
            dag-result (dag-orch/execute-plan-as-dag plan ctx-with-resume)]
        (if (schema/succeeded? dag-result)
          (apply-dag-success ctx dag-result pipeline
                             transition-to-completed-fn
                             transition-to-failed-fn)
          (apply-dag-failure ctx dag-result transition-to-failed-fn))))))

;------------------------------------------------------------------------------ Layer 2: Phase step execution

(defn execute-phase-step
  "Execute a single phase step and return updated context.

   Arguments:
   - pipeline: Vector of interceptors
   - ctx: Current execution context
   - callbacks: Map with :on-phase-start, :on-phase-complete
   - merge-metrics-fn: Function to merge phase metrics
   - transition-to-completed-fn: Function to transition to completed state
   - transition-to-failed-fn: Function to transition to failed state

   Returns updated context."
  [pipeline ctx callbacks merge-metrics-fn transition-to-completed-fn transition-to-failed-fn]
  (let [phase-index (:execution/phase-index ctx)
        interceptor (get pipeline phase-index)
        phase-name (get-in interceptor [:config :phase])
        {:keys [on-phase-start on-phase-complete]} callbacks
        ctx-with-phase (assoc ctx :execution/current-phase phase-name)]

    ;; Notify phase start
    (when on-phase-start
      (on-phase-start ctx-with-phase interceptor))

    ;; Execute phase lifecycle: enter -> gates -> leave
    (let [[ctx-after-lifecycle phase-result] (execute-phase-lifecycle interceptor ctx-with-phase)
          ;; Process result: response chain + metrics + files + artifacts
          ctx-processed (process-phase-result ctx-after-lifecycle phase-name phase-result merge-metrics-fn)]

      ;; Notify phase complete
      (when on-phase-complete
        (on-phase-complete ctx-processed interceptor phase-result))

      ;; After plan phase, attempt DAG parallelization before normal transition
      (or (try-dag-execution ctx-processed phase-name phase-result pipeline
                             transition-to-completed-fn transition-to-failed-fn)
          ;; Normal transition (non-plan phases, or plan not parallelizable)
          (let [event (determine-phase-event (:config interceptor)
                                             phase-result)]
            (apply-phase-transition ctx-processed event pipeline
                                    transition-to-completed-fn
                                    transition-to-failed-fn))))))
