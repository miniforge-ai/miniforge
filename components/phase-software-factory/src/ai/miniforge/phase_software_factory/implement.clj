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
(ns ai.miniforge.phase-software-factory.implement
  "Implementation phase interceptor.

   Generates code artifacts from plans.
   Agent: :implementer
  Default gates: [:syntax :lint]"
  (:require
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.interface.gate :as phase-gate]
   [ai.miniforge.phase-software-factory.messages :as messages]
   [ai.miniforge.phase-software-factory.phase-config :as phase-config]
   [ai.miniforge.phase-software-factory.codex-pin :as codex-pin]
   [ai.miniforge.phase-software-factory.gap-wiring :as gap-wiring]
   [ai.miniforge.phase-software-factory.knowledge-helpers :as kb-helpers]
   [ai.miniforge.phase-software-factory.implement-context :as impl-context]
   [ai.miniforge.phase-software-factory.implement-result :as implement-result]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.context-pack.interface :as context-pack]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.phase-software-factory.phase-terminal :as phase-terminal]))

;------------------------------------------------------------------------------ Layer 0

;; Defaults
(def ^{:stratum 0} default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :implement))

;; Interceptor implementation
(defn ^{:stratum 0} error-implement
  "Handle implementation phase errors. Attempts repair via inner loop
   within budget; on exhaustion redirects via `:on-fail` when set,
   otherwise propagates. Delegates to the shared `phase/handle-error`
   helper."
  [ctx ex]
  (phase/handle-error ctx ex 5))

(defn ^{:stratum 0} build-implement-task
  "Build the task map for the implementer agent from execution context.
   Returns {:task task-map :rules-manifest manifest-or-nil}."
  [ctx]
  (let [input (get-in ctx [:execution/input])
        plan-result (get-in ctx [:execution/phase-results :plan :result :output])
        verify-failure (get-in ctx [:execution/phase-results :verify])
        worktree-path (impl-context/resolve-worktree-path ctx)
        files-in-scope (impl-context/resolve-files-in-scope input)
        pack-ctx (or (get-in ctx [:execution/pack-context])
                     (impl-context/build-context-pack worktree-path files-in-scope))
        existing-files (impl-context/resolve-existing-files ctx pack-ctx worktree-path files-in-scope)
        ;; Thesium Codex blackboard pin (SPEC §7.4): pinned FIRST so the
        ;; worries render before the content they apply to. Retry iterations
        ;; reuse :execution/cached-files which already contains the previous
        ;; pin — drop it so the fresh consultation replaces it instead of
        ;; rendering twice.
        codex-outcome (codex-pin/pin-outcome :implement (get-in ctx [:execution/logger]))
        existing-files (vec (remove #(= codex-pin/pin-path (:path %))
                                    (or existing-files [])))
        existing-files (if-let [pin (:entry codex-outcome)]
                         (into [pin] existing-files)
                         existing-files)
        behavior-addendum (phase/load-guidance-addendum
                            :implement {:task {:task/intent (:intent input)}})
        review-feedback (impl-context/resolve-review-feedback ctx)
        phase-handoff (impl-context/resolve-phase-handoff ctx)
        ;; A prior implement attempt DENIED by gates: its structured gate
        ;; errors ride :execution/phase-results (the [:phase ...] channel
        ;; is cleared before every step). This is what turns a gate from
        ;; a wall into a repair instruction — deny, retry with the
        ;; evidence, fix, pass.
        gate-failures (impl-context/prior-gate-failures ctx)
        {:keys [formatted manifest]} (kb-helpers/inject-with-manifest
                                       (:knowledge-store ctx) :implementer (get input :tags []))
        base-task (impl-context/assoc-optional-task-fields
                    {:task/id (random-uuid)
                     :task/type :implement
                     :task/description (:description input)
                     :task/title (:title input)
                     :task/intent (:intent input)
                     :task/constraints (:constraints input)
                     :task/plan plan-result
                     :task/existing-files existing-files
                     :task/behavior-addendum behavior-addendum}
                    pack-ctx formatted)
        iteration (get-in ctx [:phase :iterations] 0)
        last-error (get-in ctx [:phase :last-error])
        task (cond-> base-task
               verify-failure
               (assoc :task/verify-failures (impl-context/build-verify-failures verify-failure))
               gate-failures
               (assoc :task/gate-failures (vec gate-failures))
               review-feedback
               (assoc :task/review-feedback review-feedback)
               phase-handoff
               (assoc :task/phase-handoff phase-handoff)
               (pos? iteration)
               (assoc :task/prior-attempts
                      {:attempt-number (inc iteration)
                       :prior-error    (or last-error (messages/t :implement/prior-error-default))
                       :instruction    (messages/t :implement/retry-instruction)}))]
    {:task task
     :rules-manifest manifest
     :codex-outcome codex-outcome}))

(defn- ^{:stratum 0} run-stopped?
  "True when the run's control state, carried in `ctx`'s execution opts,
   is stopped. The runner records the stop when the phase returns."
  [ctx]
  (boolean (some-> (get-in ctx [:execution/opts :control-state]) deref :stopped)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} leave-implement
  "Post-processing for implementation phase.

   Records metrics, captures code artifacts."
  [ctx]
  ;; Gap-instrument miss recording (T2 s3): best-effort, opt-in, and it
  ;; must never change the outcome it measures.
  (gap-wiring/record-phase-misses! ctx :implement)
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (if start-time (- end-time start-time) 0)
        logger (get-in ctx [:execution/logger])
        result (get-in ctx [:phase :result])
        agent-status (:status result)
        rate-limited? (and (= :error agent-status) (implement-result/rate-limit-in-result? result))
        ;; PR-C of network-resilience stack: PR-B's network-monitor
        ;; flagged a sustained provider outage. Recovery is the
        ;; opposite of rate-limited — burn an iteration to retry from
        ;; the persisted checkpoint rather than pause/wait, since the
        ;; subprocess is already dead and the LLM call's state was
        ;; mid-stream when the drop hit.
        network-dropped? (and (= :error agent-status)
                              (implement-result/network-drop-in-result? result))
        ;; PR-C of phase-timeout stack: companion to network-dropped?.
        ;; Stream-idle / stagnation / hard-limit timeouts from the
        ;; implementer LLM call. Retriable from the persisted
        ;; checkpoint up to the iteration budget (same recovery shape
        ;; as network-dropped); after budget exhaustion the verdict
        ;; below escalates to `:implement/backend-timeout` terminal.
        ;; Two arms never both fire on the same result — the
        ;; underlying `:timeout :type` is mutually exclusive.
        backend-timeout? (and (= :error agent-status)
                              (implement-result/backend-timeout-in-result? result))
        gate-failed? (phase-gate/gate-failed? (:phase ctx))
        ;; Curator's empty-diff verdict: the implementer wrote no files to the
        ;; environment. Retrying with the same prompt won't change that — the
        ;; next attempt would just burn another 10+ minutes producing nothing.
        ;; This is the hotfix that makes M0a's signal actually stop the burn.
        ;; TODO: M1 subsumes this into a proper FSM where terminal error codes
        ;; are a first-class concept.
        curator-empty-diff? (= :curator/no-files-written
                               (get-in result [:error :data :code]))
        ;; Stream watchdog outcome threaded from enter-implement. A stalled run
        ;; is retriable, not terminal — the agent hung silently, so the next
        ;; iteration may succeed.
        watchdog-state (get-in ctx [:phase :watchdog-state])
        stalled? (:stalled? watchdog-state)
        iterations (or (get-in ctx [:phase :iterations]) 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        ;; In the environment promotion model the agent writes files directly to
        ;; the executor environment. A nil output is NOT an error — code is in
        ;; the environment, not serialized in the result.
        effective-status agent-status
        invalid-already-implemented? (implement-result/unverified-already-implemented? ctx result)
        phase-status (cond
                       ;; Stream watchdog stall — retriable. Route through the
                       ;; normal status determination so the phase retries on
                       ;; the next iteration rather than terminating.
                       stalled? (phase/determine-phase-status
                                  effective-status iterations max-iterations)
                       gate-failed? :failed
                       invalid-already-implemented? :failed
                       (= :already-implemented agent-status) :already-implemented
                       ;; Rate limit: fail immediately, don't burn retry budget
                       rate-limited? :failed
                       ;; Network drop: retriable from the last persisted
                       ;; checkpoint. Route through determine-phase-status
                       ;; so the iteration budget caps the retry count —
                       ;; after max-iterations consecutive network-drop
                       ;; failures the verdict below escalates to
                       ;; :implement/network-dropped (terminal).
                       network-dropped? (phase/determine-phase-status
                                          effective-status iterations max-iterations)
                       ;; Backend timeout (stream-idle / stagnation /
                       ;; hard-limit): retriable within the iteration
                       ;; budget — same shape as network-dropped.
                       ;; Provider may be transiently slow; retry from
                       ;; the persisted checkpoint. After
                       ;; max-iterations the verdict below escalates
                       ;; to `:implement/backend-timeout` terminal.
                       backend-timeout? (phase/determine-phase-status
                                          effective-status iterations max-iterations)
                       ;; Curator said no files — terminal, no retry.
                       curator-empty-diff? :failed
                       :else (phase/determine-phase-status
                               effective-status iterations max-iterations))
        metrics (implement-result/ensure-valid-metrics
                  (get result :metrics {:tokens 0 :duration-ms duration-ms})
                  logger)
        ;; The agent's cost, or nil when it is unknown (an unpriced
        ;; model). No flat-rate guess: an unknown cost stays off the
        ;; phase metrics, and the workflow marks its total
        ;; :cost-unavailable? instead of showing a made-up figure. An
        ;; agent's `:cost-usd nil` (an unpriced no-op) is dropped, not kept.
        cost-usd (get result :cost-usd (get metrics :cost-usd))
        metrics (cond-> (-> metrics (dissoc :cost-usd) (assoc :duration-ms duration-ms))
                  (some? cost-usd) (assoc :cost-usd cost-usd))
        env-id (get ctx :execution/environment-id)
        curated-artifact (implement-result/successful-curated-artifact result)
        degraded-handoff? (true? (:degraded-handoff? result))
        raw-agent-status (or (:raw-agent-status result) agent-status)
        summary (or (get-in result [:output :code/summary])
                    (implement-result/raw-agent-output result)
                    (messages/t :implement/summary-default))
        on-fail (get-in ctx [:phase-config :on-fail])
        ;; Phase 3c verdict — only meaningful for terminal phase
        ;; statuses (:completed → :approved; :failed → one of the
        ;; failure verdicts). During :retrying / :already-implemented
        ;; the FSM doesn't see a transition that needs verdict input.
        verdict (cond
                  (= :completed phase-status)
                  :approved

                  (not= :failed phase-status)
                  nil

                  rate-limited?
                  :implement/rate-limited

                  ;; Network drop: only escalates to terminal verdict
                  ;; when phase-status above resolved to :failed (i.e.
                  ;; the iteration budget was exhausted). While
                  ;; phase-status is :retrying the verdict cond returns
                  ;; nil per the (not= :failed phase-status) clause
                  ;; above and the FSM keeps cycling implement.
                  network-dropped?
                  :implement/network-dropped

                  ;; Backend timeout (PR-C of phase-timeout stack):
                  ;; companion to network-dropped — same iteration-
                  ;; budget retry shape, fires when the implementer's
                  ;; LLM call hit stream-idle / stagnation / hard-limit
                  ;; for max-iterations consecutive cycles. Terminal:
                  ;; retrying would just hit the same slow provider
                  ;; on the next pass. The FSM's `:verdict/terminal?`
                  ;; guard routes this straight to `:failed` without
                  ;; the on-fail :implement loop re-firing.
                  backend-timeout?
                  :implement/backend-timeout

                  curator-empty-diff?
                  :implement/empty-diff

                  invalid-already-implemented?
                  :implement/already-implemented-invalid

                  (some? on-fail)
                  :repair-requested

                  :else
                  :exhausted)
        updated-ctx (cond-> ctx
                      true
                      (-> (assoc-in [:phase :ended-at] end-time)
                          (assoc-in [:phase :duration-ms] duration-ms)
                          (assoc-in [:phase :status] phase-status)
                          (assoc-in [:phase :metrics] metrics))
                      verdict
                      (-> (assoc-in [:phase :verdict] verdict)
                          (assoc-in [:phase :result :output :phase/verdict] verdict)))
        updated-ctx (-> updated-ctx
                        (assoc-in [:metrics :implementation :duration-ms] duration-ms)
                        (assoc-in [:metrics :implementation :repair-cycles] (dec iterations))
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :cost-usd] (fnil + 0.0) (or cost-usd 0.0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Capture inner-loop learning when repair succeeded (iterations > 1, completed)
    (when (= :completed phase-status)
      (knowledge/capture-repair-learning!
       (:knowledge-store ctx) :implementer
       (get-in ctx [:execution/input :title]) iterations))
    ;; Handle retrying, failure, completed, or already-implemented outcomes
    (let [final-ctx
          (cond-> updated-ctx
            (contains? #{:completed :already-implemented} phase-status)
            (update-in [:execution :phases-completed] (fnil conj []) :implement)

            ;; On success: store lightweight result — code is in the environment, not here
            (= :completed phase-status)
            (-> (assoc-in [:phase :result]
                          (implement-result/preserve-consultation (phase/success env-id summary) result))
                (assoc-in [:phase :artifact]
                          (cond-> (implement-result/lightweight-curated-artifact curated-artifact)
                            degraded-handoff?
                            (assoc :code/degraded-handoff? true
                                   :code/raw-agent-status raw-agent-status
                                   :code/raw-error (:raw-error result)))))
            (phase/retrying? (:phase updated-ctx))
            (-> (update-in [:phase :iterations] (fnil inc 1))
                (assoc-in [:phase :last-error]
                          (implement-result/extract-error-message result (messages/t :implement/agent-error))))

            (= :failed phase-status)
            (assoc-in [:phase :error]
                      (if invalid-already-implemented?
                        (implement-result/build-unverified-already-implemented-error ctx result iterations)
                        (implement-result/build-phase-error result agent-status rate-limited? iterations)))

            (and (= :already-implemented agent-status)
                 (not invalid-already-implemented?))
            (assoc-in [:phase :skipped-reason] :already-implemented))]
      ;; Emit phase-completed telemetry with termination reason
      (phase/emit-phase-completed! final-ctx :implement
        (merge {:outcome     (phase/outcome result
                                            (contains? #{:completed :already-implemented} phase-status))
                :duration-ms duration-ms
                :tokens      (get metrics :tokens 0)}
               (phase-terminal/derive-termination-reason
                 result (merge watchdog-state {:rate-limited? rate-limited?}))))
      final-ctx)))

(defn ^{:stratum 1} enter-implement
  "Execute implementation phase.

   Reads plan from context, invokes implementer agent,
   runs through inner loop with syntax/lint gates."
  [ctx]
  (let [config (phase/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)
        logger (or (get-in ctx [:execution/logger])
                   (log/create-logger {:min-level :info :output :human}))
        implementer-agent (agent/create-implementer {:logger logger})
        {:keys [task rules-manifest codex-outcome]} (build-implement-task ctx)
        ;; Cache loaded files and context pack for subsequent retries
        ctx (cond-> ctx
              ;; Resolved once and carried: the artifact collector, the
              ;; :no-secrets gate and every later persistence boundary
              ;; measure the change from the same resume-aware base.
              (not (get ctx :execution/task-base-refs))
              (assoc :execution/task-base-refs (impl-context/task-base-refs ctx))
              (not (get-in ctx [:execution/cached-files]))
              (assoc-in [:execution/cached-files] (:task/existing-files task))
              (and (:task/context-pack task) (not (get-in ctx [:execution/pack-context])))
              (assoc-in [:execution/pack-context]
                        (context-pack/->pack-context
                          (:task/repo-index task)
                          (:task/context-pack task))))
        on-chunk (impl-context/create-streaming-callback ctx)
        peer-advice (impl-context/collect-peer-advice ctx)
        task (cond-> task
               peer-advice (assoc :task/peer-advice peer-advice))
        ;; Arm the stream watchdog around the synchronous agent invocation.
        ;; Only when the run is actually streaming (on-chunk present): the
        ;; watchdog resets its timer on each chunk, so a non-streaming run has
        ;; no ping source and must not be armed (it would false-fire). On the
        ;; streaming happy path it is observe-only — every chunk pings it and
        ;; it is stopped in a finally. On a silent stall it interrupts the
        ;; phase thread, unwinding the in-flight llm-stream-reader subprocess.
        backend (impl-context/resolve-backend-keyword ctx)
        watchdog-config (or (get-in ctx [:execution/opts :self-healing])
                            (get-in ctx [:user-config :self-healing])
                            {})
        event-stream (phase/resolve-event-stream ctx)
        workflow-id (or (:execution/id ctx) (:workflow/id ctx))
        phase-thread (Thread/currentThread)
        wd (when on-chunk
             (agent/create-watchdog
              (cond-> {:threshold-ms (agent/resolve-gap-threshold watchdog-config backend)
                       :phase-id :implement
                       :backend backend
                       :event-stream event-stream
                       :workflow-id workflow-id
                       :logger logger
                       :kill-fn #(.interrupt phase-thread)}
                ;; Optional override (mainly for tests/tuning); create-watchdog
                ;; defaults this when absent — never pass nil.
                (get watchdog-config :agent/stream-check-interval-ms)
                (assoc :check-interval-ms
                       (get watchdog-config :agent/stream-check-interval-ms)))))
        agent-ctx (cond-> ctx
                    on-chunk (assoc :on-chunk
                                    (fn [chunk]
                                      (agent/ping-watchdog! wd)
                                      (on-chunk chunk))))
        impl-result (try
                      (agent/invoke implementer-agent task agent-ctx)
                      (catch Exception e
                        (response/failure e))
                      (finally
                        (agent/stop-watchdog! wd)))
        stalled? (agent/watchdog-stalled? wd)
        gap-ms (agent/watchdog-stall-gap-ms wd)
        ;; Clear any leftover interrupt flag set by the kill-fn so it does not
        ;; poison the curator run or subsequent work on this thread.
        _ (when stalled? (Thread/interrupted))
        ;; A cancelled agent turn gets no curator; a turn that succeeded keeps its work.
        cancelled-turn? (and (run-stopped? ctx) (response/error? impl-result))
        ;; Curator post-processes the implementer's environment writes into a
        ;; structured :code artifact. The environment is the artifact, so we
        ;; invoke the curator regardless of the implementer's self-reported
        ;; status — a "failure" due to unparseable LLM output may still have
        ;; produced files on disk (observed in the 2026-04-16 dogfood). When
        ;; the implementer truly wrote nothing, the curator fast-fails with a
        ;; clear "no files" error instead of silently retrying.
        curator-result
        (if cancelled-turn?
          impl-result
          (try
            (agent/curate-implement-output
             {:implementer-result impl-result
              :env-id (get ctx :execution/environment-id)
              ;; Capsule collection reads the capsule's checkout, not the host path.
              :worktree-path (or (when (get ctx :execution/execute-fn)
                                   (get ctx :execution/capsule-workdir))
                                 (impl-context/resolve-worktree-path ctx))
              :executor (get ctx :execution/executor)
              :execute-fn (get ctx :execution/execute-fn)
              :pre-session-snapshot (get ctx :execution/pre-session-snapshot)
              :base-refs (get ctx :execution/task-base-refs)
              :intent-scope (get-in ctx [:execution/input :intent :scope])
              :spec-description (get-in ctx [:execution/input :description])
              :llm-client (get ctx :llm-backend)
              :logger logger})
            (catch Exception e
              (response/failure e))))
        ;; The curator's verdict is the source of truth about whether the
        ;; implementer produced useful work (the environment is the artifact).
        ;; Its terminal :code (e.g. :curator/no-files-written) carries
        ;; retry-or-terminate signal the phase runner depends on, so when the
        ;; curator returned an error we propagate IT, not the implementer's
        ;; symptom-level error. If the implementer also errored, that context
        ;; is preserved in the event stream separately.
        ;; Fix for 2026-04-17 dogfood: previously impl-result won on dual-error,
        ;; dropping the curator's :code and leaving leave-implement's retry
        ;; gate blind — phase retried 6×.
        curator-terminal?
        (= :curator/no-files-written
           (get-in curator-result [:error :data :code]))
        already-implemented-noop?
        (= :already-implemented (:status impl-result))
        ;; Surface the curator's collection diagnostic (per-source file counts,
        ;; pre-session-snapshot?, collection-mode, worktree-path) when the
        ;; no-files result is actually propagated as the phase failure. It is
        ;; computed in curate but otherwise only buried in the error data —
        ;; emitting it makes "implementer wrote files but curator saw none"
        ;; debuggable from the event stream instead of requiring a worktree
        ;; autopsy (which is gone after cleanup). Gated on
        ;; (not already-implemented-noop?) so the legitimate
        ;; success-by-prior-work path (handled below) stays quiet.
        _ (when (and curator-terminal? (not already-implemented-noop?))
            (log/warn logger :implement :implement/curator-no-files-diagnostic
                      {:data (get-in curator-result [:error :data :diagnostic])}))
        ;; "Degraded handoff" means the implementer agent reported a hard
        ;; error but the curator recovered files anyway. :already-implemented
        ;; is a deliberate skip (work was completed in a prior turn) — the
        ;; agent is correctly reporting success-by-prior-work, NOT failing.
        ;; Don't mark such handoffs as degraded; the reviewer's handoff
        ;; gate would otherwise auto-reject every repair iteration that
        ;; legitimately recognized "already done."
        ;;
        ;; response/error? recognises every error-shape the implementer
        ;; can return: :status :error (response/error), :status :failed
        ;; (legacy phase shape), and :success false (response/failure
        ;; from a thrown exception in agent/invoke). :already-implemented
        ;; matches none of these, so it correctly bypasses the flag.
        impl-errored? (response/error? impl-result)
        metadata-only-submit?
        (and (= :narrative-only-response
                (get-in impl-result [:error :data :reject/reason]))
             (= :mcp (get-in impl-result [:error :data :artifact-source])))
        degraded-handoff? (and impl-errored? (not metadata-only-submit?))
        result (cond
                 ;; A cancel before or during a failed curator; the agent's spend is kept.
                 (or cancelled-turn?
                     (and (run-stopped? ctx) (not (phase/result-succeeded? curator-result))))
                 (merge (response/error (messages/t :implement/run-stopped) {:data {:code :run-stopped}})
                        (select-keys impl-result [:metrics :cost-usd]))
                 ;; Stream watchdog fired: the agent stalled silently and was
                 ;; killed mid-stream, so the empty diff is a stall symptom, not
                 ;; a genuine no-files verdict. Re-code the implementer error as
                 ;; :agent-stalled so downstream curator-terminal?/empty-diff?
                 ;; gates treat it as RETRIABLE rather than terminal.
                 stalled?
                 (assoc-in impl-result [:error :data :code] :agent-stalled)
                 ;; Curator found files — use its artifact, even if the
                 ;; implementer reported error. Merge implementer metrics through.
                 (phase/result-succeeded? curator-result)
                 (-> impl-result
                     (assoc :status :success
                            :success? true)
                     (assoc :output (:output curator-result))
                     (cond-> degraded-handoff?
                       (assoc :degraded-handoff? true
                              :raw-agent-status (:status impl-result)
                              :raw-error (:error impl-result)))
                     (dissoc :error)
                     (update :metrics merge (:metrics curator-result))
                     (implement-result/with-implementer-spend impl-result))
                 ;; A verified no-op is legitimate implement success-by-prior-work.
                 ;; The curator only sees an empty diff, so its terminal
                 ;; :curator/no-files-written verdict cannot distinguish
                 ;; "task already satisfied" from "agent did nothing." Preserve
                 ;; the implementer's normalized :already-implemented result here
                 ;; and let the outer phase boundary apply the usual repair-loop
                 ;; guards in leave-implement.
                 (and already-implemented-noop? curator-terminal?)
                 impl-result
                 ;; Curator said no-files (terminal). This wins over any
                 ;; implementer error — the implementer's error is the symptom,
                 ;; the empty diff is the root cause the phase runner needs.
                 curator-terminal?
                 (implement-result/with-implementer-spend curator-result impl-result)
                 ;; Implementer errored for a non-curator reason (rate-limit,
                 ;; exception, etc.). Propagate — the phase runner classifies
                 ;; those via rate-limited? / existing branches.
                 (not (phase/result-succeeded? impl-result))
                 impl-result
                 ;; Implementer reported success but curator still returned
                 ;; something non-success (shouldn't happen outside no-files;
                 ;; defensive fallback).
                 :else
                 (implement-result/with-implementer-spend curator-result impl-result))
        ;; SPEC §7.4.3: mark the result with consultation provenance BEFORE
        ;; enter-context stores it — gates run between enter and leave and
        ;; read [:result :output]. Reads come from the agent result's
        ;; :context-reads (host-mode sessions); curator-result branches drop
        ;; that key, so read it off impl-result directly. Attached on
        ;; FAILURES too (:output starts nil on response/failure): a failed
        ;; implement that consulted must not be ledgered as one that never
        ;; did — the gap instrument's :undelivered bucket depends on it.
        result (codex-pin/attach-consultation
                 result
                 (codex-pin/consultation-summary
                   codex-outcome
                   (:context-reads impl-result)
                   ;; §7.7.2 explicit answers: same off-impl-result read as
                   ;; context-reads — curator-result branches drop the key.
                   (:codex-answers impl-result)
                   ;; §7.7.2 lost-vs-unanswered: the session's answer-log
                   ;; marker (:absent|:recorded|:unreadable).
                   (:codex-answer-log impl-result)))
        ;; The refs the artifact collector diffs against travel with the
        ;; artifact, so the :no-secrets gate measures the change from the
        ;; same task base rather than from HEAD.
        result (cond-> result
                 (map? (:output result))
                 (assoc-in [:output :code/base-refs] (get ctx :execution/task-base-refs)))]
    (-> (phase/enter-context ctx :implement :implementer gates budget start-time result)
        (assoc-in [:phase :rules-manifest] rules-manifest)
        (assoc-in [:phase :watchdog-state]
                  {:stalled? stalled? :stall/gap-duration-ms gap-ms}))))

;------------------------------------------------------------------------------ Layer 2

;; Registry method
(defmethod ^{:stratum 2} phase/get-phase-interceptor-method :implement
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::implement
     :config merged
     :enter (fn [ctx]
              (enter-implement (assoc ctx :phase-config merged)))
     :leave leave-implement
     :error error-implement}))

;; Register defaults on load
(phase/register-phase-defaults! :implement default-config)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get implement interceptor with defaults
  (phase/get-phase-interceptor {:phase :implement})

  ;; Get with overrides
  (phase/get-phase-interceptor {:phase :implement
                                   :budget {:tokens 50000}
                                   :gates [:syntax :lint :no-secrets]})

  ;; Check defaults
  (phase/phase-defaults :implement)

  :leave-this-here)
