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
  (:require [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.dag-orchestrator :as dag-orch]))

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
                  (assoc :execution/status :failed)
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

(defn determine-next-index
  "Determine next phase index based on result and config.

   Arguments:
   - pipeline: Vector of phase interceptors
   - current-index: Current phase index
   - phase-config: Current phase configuration
   - phase-result: Result from phase execution

   Returns next index or :done/:error."
  [pipeline current-index phase-config phase-result]
  (let [on-fail (:on-fail phase-config)
        on-success (:on-success phase-config)
        redirect-to (:redirect-to phase-result)]
    (cond
      ;; Phase retrying (transient error, stay at current index)
      (phase/retrying? phase-result)
      current-index

      ;; Phase already done — skip to done
      (phase/already-done? phase-result)
      (let [done-index (->> pipeline
                            (map-indexed vector)
                            (filter #(= :done (get-in (second %) [:config :phase])))
                            (first))]
        (if done-index (first done-index) :done))

      ;; Phase completed successfully
      (phase/succeeded? phase-result)
      (if on-success
        ;; Find target phase by name
        (let [target-index (->> pipeline
                                (map-indexed vector)
                                (filter #(= on-success (get-in (second %) [:config :phase])))
                                (first))]
          (if target-index (first target-index) (inc current-index)))
        ;; Default: next phase
        (let [next-idx (inc current-index)]
          (if (< next-idx (count pipeline))
            next-idx
            :done)))

      ;; Phase failed with redirect — jump to target phase
      (and (phase/failed? phase-result) redirect-to)
      (let [target-index (->> pipeline
                              (map-indexed vector)
                              (filter #(= redirect-to (get-in (second %) [:config :phase])))
                              (first))]
        (if target-index (first target-index) :error))

      ;; Phase failed
      (phase/failed? phase-result)
      (if on-fail
        ;; Find target phase by name
        (let [target-index (->> pipeline
                                (map-indexed vector)
                                (filter #(= on-fail (get-in (second %) [:config :phase])))
                                (first))]
          (if target-index
            (first target-index)
            :error))
        :error)

      ;; Default: move to next
      :else (inc current-index))))

(def max-redirects
  "Maximum number of phase redirects before failing to prevent infinite loops."
  5)

(defn apply-phase-transition
  "Apply phase transition based on next-index.

   Returns context with updated status/phase-index or terminal state."
  [ctx next-index pipeline transition-to-completed-fn transition-to-failed-fn]
  (let [current-index (:execution/phase-index ctx)
        is-redirect? (and (number? next-index) (not= next-index (inc current-index)))
        redirect-count (get ctx :execution/redirect-count 0)]
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

      (= :done next-index)
      (transition-to-completed-fn ctx)

      (= :error next-index)
      (transition-to-failed-fn ctx)

      (number? next-index)
      (if (< next-index (count pipeline))
        (cond-> (assoc ctx :execution/phase-index next-index)
          is-redirect? (update :execution/redirect-count (fnil inc 0)))
        (transition-to-completed-fn ctx))

      :else
      (let [anom (response/make-anomaly
                   :anomalies.workflow/invalid-transition
                   (str "Invalid next index: " next-index))]
        (-> ctx
            (update :execution/errors conj
                    {:type :invalid-transition
                     :message (str "Invalid next index: " next-index)
                     :anomaly anom})
            (update :execution/response-chain
                    response/add-failure :pipeline
                    anom
                    {:error (str "Invalid next index: " next-index)
                     :next-index next-index})
            (transition-to-failed-fn))))))

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

(defn- summarize-error
  "Keys-only/trimmed summary of an error map so the event carries enough
   to diagnose without leaking large payloads (stack traces, token arrays).
   Returns nil when err is not a usable map."
  [err]
  (when (map? err)
    (let [msg (or (:message err) (:error/message err))
          data-keys (when (map? (:data err)) (sort (keys (:data err))))]
      (cond-> {}
        msg                              (assoc :error/message
                                                (subs (str msg) 0 (min 500 (count (str msg)))))
        (keyword? (:error/category err)) (assoc :error/category (:error/category err))
        (keyword? (:anomaly err))        (assoc :anomaly (:anomaly err))
        (seq data-keys)                  (assoc :error/data-keys (vec data-keys))))))

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
                         (only when :result/status is :error / :failed / :failure)}"
  [phase-result reason]
  (let [phase-keys (when (map? phase-result) (sort (keys phase-result)))
        result     (:result phase-result)
        result-keys (when (map? result) (sort (keys result)))
        result-status (when (map? result) (:status result))
        output (when (map? result) (:output result))
        output-type (classify-output output)
        output-keys (when (= :map output-type) (sort (keys output)))
        has-plan-id? (when (= :map output-type)
                       (boolean (:plan/id output)))
        plan (extract-plan-from-phase-result phase-result)
        error-summary (when (contains? #{:error :failed :failure} result-status)
                        (summarize-error (:error result)))]
    (cond-> {:phase-result/keys (vec phase-keys)
             :result/keys       (vec result-keys)
             :result/status     result-status
             :output/type       output-type}
      (seq output-keys)       (assoc :output/keys (vec output-keys))
      (some? has-plan-id?)    (assoc :output/has-plan-id? has-plan-id?)
      (seq error-summary)     (assoc :result/error error-summary)
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
      (let [{:keys [out]} (clojure.java.shell/sh
                            "git" "diff" "--name-only" "HEAD"
                            :dir sub-wt)
            changed-files (remove clojure.string/blank?
                                  (clojure.string/split-lines (or out "")))]
        (doseq [f changed-files]
          (let [src (clojure.java.io/file sub-wt f)
                dst (clojure.java.io/file parent-worktree f)]
            (when (.exists src)
              (clojure.java.io/make-parents dst)
              (clojure.java.io/copy src dst)))))
      (catch Exception _e nil))))

(defn apply-dag-success
  "Apply a successful DAG result to the execution context.
   Merges artifact provenance, copies sub-worktree file changes into the
   parent worktree, synthesizes an :implement phase result, and advances
   past implement to verify → review → release."
  [ctx dag-result pipeline transition-to-completed-fn]
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
        post-impl-idx (index-after-phase pipeline :implement)]
    (if (and post-impl-idx (< post-impl-idx (count pipeline)))
      (assoc ctx-with-dag :execution/phase-index post-impl-idx)
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
          (apply-dag-success ctx dag-result pipeline transition-to-completed-fn)
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
          (let [next-index (determine-next-index pipeline phase-index
                                                 (:config interceptor)
                                                 phase-result)]
            (apply-phase-transition ctx-processed next-index pipeline
                                    transition-to-completed-fn
                                    transition-to-failed-fn))))))
