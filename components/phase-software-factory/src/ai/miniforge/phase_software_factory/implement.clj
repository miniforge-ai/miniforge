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
   [ai.miniforge.phase-software-factory.messages :as messages]
   [ai.miniforge.phase-software-factory.phase-config :as phase-config]
   [ai.miniforge.phase-software-factory.knowledge-helpers :as kb-helpers]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.context-pack.interface :as context-pack]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.repo-index.interface :as repo-index]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.phase-software-factory.phase-terminal :as phase-terminal]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :implement))

;; Register defaults on load
(phase/register-phase-defaults! :implement default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(def ^:private max-test-output-lines
  "Cap raw test output sent to implementer to avoid token waste.
   Failure summaries are typically in the first/last 30 lines."
  60)

(def ^:private current-head-ref
  "Git ref for the currently acquired promoted workspace."
  "HEAD")

(defn- truncate-test-output
  "Truncate test output to max-test-output-lines, keeping head and tail."
  [output]
  (when (string? output)
    (let [lines (str/split-lines output)
          n (count lines)]
      (if (<= n max-test-output-lines)
        output
        (let [head-n 30
              tail-n 25
              head (take head-n lines)
              tail (take-last tail-n lines)
              skipped (- n head-n tail-n)]
          (str/join "\n"
            (concat head
                    [(str "\n... [" skipped " lines omitted] ...\n")]
                    tail)))))))

(defn- build-context-pack
  "Build a context pack for the implement phase, falling back gracefully."
  [worktree-path files-in-scope]
  (try
    (when-let [index (repo-index/build-index worktree-path)]
      (let [search-index (try (repo-index/build-search-index index)
                              (catch Exception _ nil))
            pack (context-pack/build-pack :implement index
                   {:files-in-scope files-in-scope
                    :search-index search-index
                    :search-query (when (seq files-in-scope)
                                    (first files-in-scope))})]
        (context-pack/->pack-context index pack)))
    (catch Exception _e
      nil)))

(defn- build-verify-failures
  "Extract lean verify-failure data from phase results.
   In the environment model, test metrics are in :result :metrics."
  [verify-result]
  {:test-results {:pass-count (get-in verify-result [:result :metrics :pass-count] 0)
                  :fail-count (get-in verify-result [:result :metrics :fail-count] 0)
                  :summary    (get-in verify-result [:result :summary])}
   :test-output (truncate-test-output
                 (get-in verify-result [:result :metrics :test-output]))})

(defn- resolve-worktree-path
  "Resolve the worktree path from execution context.
   Fails fast if no environment has been acquired — do not fall back to host filesystem."
  [ctx]
  (or (get ctx :execution/worktree-path)
      (throw (ex-info (messages/t :implement/no-worktree)
                      {:ctx-keys (keys ctx)}))))

(defn- base-ref-candidates
  "Return candidate refs for collecting the promoted task artifact."
  [ctx]
  (letfn [(present-string? [value]
            (and (string? value) (not (str/blank? value))))
          (branch-refs [branch]
            (when (present-string? branch)
              [(str "refs/remotes/origin/" branch)
               (str "origin/" branch)
               (str "refs/heads/" branch)]))]
    (let [base-sha (get-in ctx [:execution/environment-metadata :base-sha])
          base-branch (or (get-in ctx [:execution/environment-metadata :base-branch])
                          (get-in ctx [:execution/opts :branch])
                          (get-in ctx [:execution/input :branch]))
          resume-workspace (get-in ctx [:execution/opts :resume-workspace])
          original-commit (get-in ctx [:execution/input :context :git-commit])
          original-branch (get-in ctx [:execution/input :context :git-branch])
          original-refs (concat (when (present-string? original-commit)
                                  [original-commit])
                                (branch-refs original-branch))
          resume-ranges (when resume-workspace
                          (map #(str % "..." current-head-ref) original-refs))]
      (into []
            (comp (filter present-string?) (distinct))
            (concat resume-ranges
                    [base-sha]
                    (branch-refs base-branch))))))

(defn- resolve-files-in-scope
  "Resolve files-in-scope from input, checking context and intent."
  [input]
  (or (get-in input [:context :files-in-scope])
      (get-in input [:intent :scope])))

(defn- load-files-from-capsule
  "Read scope files from inside a task capsule via execute-fn.
   Returns seq of {:path :content} maps, same shape as phase/load-files-in-scope."
  [execute-fn executor env-id worktree-path files-in-scope]
  (when (seq files-in-scope)
    (->> files-in-scope
         (keep (fn [path]
                 (try
                   (let [full-path (str worktree-path "/" path)
                         result (execute-fn executor env-id (str "cat " full-path)
                                           {:workdir worktree-path})
                         content (get-in result [:data :stdout])]
                     (when (and content (zero? (get-in result [:data :exit-code] 1)))
                       {:path path :content content}))
                   (catch Exception _ nil))))
         vec)))

(defn- resolve-existing-files
  "Load existing files from cache, context pack, capsule, or disk."
  [ctx pack-ctx worktree-path files-in-scope]
  (or (get-in ctx [:execution/cached-files])
      (:existing-files pack-ctx)
      ;; In governed mode, read files from inside the capsule
      (when-let [execute-fn (get ctx :execution/execute-fn)]
        (load-files-from-capsule execute-fn
                                 (get ctx :execution/executor)
                                 (get ctx :execution/environment-id)
                                 worktree-path files-in-scope))
      (phase/load-files-in-scope worktree-path files-in-scope)))

(defn- resolve-review-feedback
  "Extract review feedback from phase results."
  [ctx]
  (or (get-in ctx [:execution/phase-results :review :result :output :review/feedback])
      (get-in ctx [:execution/phase-results :review :result :output :review/issues])))

(defn- resolve-phase-handoff
  "Resolve the current review repair handoff targeting implement."
  [ctx]
  (get-in ctx [:execution/phase-results :review :phase/handoff]))

(defn- assoc-optional-task-fields
  "Conditionally assoc repo-map, repo-index, context-pack, and knowledge-context onto a task."
  [task pack-ctx kb-context]
  (cond-> task
    (:repo-map-text pack-ctx)
    (assoc :task/repo-map (:repo-map-text pack-ctx))
    (:repo-index pack-ctx)
    (assoc :task/repo-index (:repo-index pack-ctx))
    (:context-pack pack-ctx)
    (assoc :task/context-pack (:context-pack pack-ctx))
    kb-context
    (assoc :task/knowledge-context kb-context)))

(defn build-implement-task
  "Build the task map for the implementer agent from execution context.
   Returns {:task task-map :rules-manifest manifest-or-nil}."
  [ctx]
  (let [input (get-in ctx [:execution/input])
        plan-result (get-in ctx [:execution/phase-results :plan :result :output])
        verify-failure (get-in ctx [:execution/phase-results :verify])
        worktree-path (resolve-worktree-path ctx)
        files-in-scope (resolve-files-in-scope input)
        pack-ctx (or (get-in ctx [:execution/pack-context])
                     (build-context-pack worktree-path files-in-scope))
        existing-files (resolve-existing-files ctx pack-ctx worktree-path files-in-scope)
        behavior-addendum (phase/load-guidance-addendum
                            :implement {:task {:task/intent (:intent input)}})
        review-feedback (resolve-review-feedback ctx)
        phase-handoff (resolve-phase-handoff ctx)
        {:keys [formatted manifest]} (kb-helpers/inject-with-manifest
                                       (:knowledge-store ctx) :implementer (get input :tags []))
        base-task (assoc-optional-task-fields
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
               (assoc :task/verify-failures (build-verify-failures verify-failure))
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
     :rules-manifest manifest}))

(defn create-streaming-callback
  "Create a streaming callback for agent output if event-stream is available."
  [ctx]
  (phase/create-streaming-callback ctx :implement))

(defn collect-peer-advice
  "Collect peer messages for the implementer agent if a message router is available."
  [ctx]
  (when-let [msg-router (:message-router ctx)]
    (try
      (let [get-msgs (requiring-resolve
                       'ai.miniforge.agent.interface.protocols.messaging/get-messages-for-agent)
            msgs (get-msgs msg-router :implementer (:execution/id ctx))]
        (when (seq msgs)
          {:peer-messages (vec msgs)}))
      (catch Exception _e nil))))

(defn- resolve-backend-keyword
  "Best-effort resolution of the configured LLM backend keyword from ctx.

   The live llm-backend client carries the keyword on its config at
   `[:llm-backend :config :backend]` — the canonical location elsewhere in
   the codebase (see agent/core) — so that wins. Falls back to the various
   user/self-healing config :llm maps, then :unknown."
  [ctx]
  (or (get-in ctx [:llm-backend :config :backend])
      (get-in ctx [:execution/opts :llm-backend :config :backend])
      (get-in ctx [:user-config :llm :backend])
      (get-in ctx [:config :llm :backend])
      (get-in ctx [:llm :backend])
      :unknown))

(defn enter-implement
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
        {:keys [task rules-manifest]} (build-implement-task ctx)
        ;; Cache loaded files and context pack for subsequent retries
        ctx (cond-> ctx
              (not (get-in ctx [:execution/cached-files]))
              (assoc-in [:execution/cached-files] (:task/existing-files task))
              (and (:task/context-pack task) (not (get-in ctx [:execution/pack-context])))
              (assoc-in [:execution/pack-context]
                        (context-pack/->pack-context
                          (:task/repo-index task)
                          (:task/context-pack task))))
        on-chunk (create-streaming-callback ctx)
        peer-advice (collect-peer-advice ctx)
        task (cond-> task
               peer-advice (assoc :task/peer-advice peer-advice))
        ;; Arm the stream watchdog around the synchronous agent invocation.
        ;; Only when the run is actually streaming (on-chunk present): the
        ;; watchdog resets its timer on each chunk, so a non-streaming run has
        ;; no ping source and must not be armed (it would false-fire). On the
        ;; streaming happy path it is observe-only — every chunk pings it and
        ;; it is stopped in a finally. On a silent stall it interrupts the
        ;; phase thread, unwinding the in-flight llm-stream-reader subprocess.
        backend (resolve-backend-keyword ctx)
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
        ;; Curator post-processes the implementer's environment writes into a
        ;; structured :code artifact. The environment is the artifact, so we
        ;; invoke the curator regardless of the implementer's self-reported
        ;; status — a "failure" due to unparseable LLM output may still have
        ;; produced files on disk (observed in the 2026-04-16 dogfood). When
        ;; the implementer truly wrote nothing, the curator fast-fails with a
        ;; clear "no files" error instead of silently retrying.
        curator-result
        (try
          (agent/curate-implement-output
           {:implementer-result impl-result
            :env-id (get ctx :execution/environment-id)
            :worktree-path (resolve-worktree-path ctx)
            :executor (get ctx :execution/executor)
            :execute-fn (get ctx :execution/execute-fn)
            :pre-session-snapshot (get ctx :execution/pre-session-snapshot)
            :base-refs (base-ref-candidates ctx)
            :intent-scope (get-in ctx [:execution/input :intent :scope])
            :spec-description (get-in ctx [:execution/input :description])
            :llm-client (get ctx :llm-backend)
            :logger logger})
          (catch Exception e
            (response/failure e)))
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
                     (update :metrics merge (:metrics curator-result)))
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
                 curator-result
                 ;; Implementer errored for a non-curator reason (rate-limit,
                 ;; exception, etc.). Propagate — the phase runner classifies
                 ;; those via rate-limited? / existing branches.
                 (not (phase/result-succeeded? impl-result))
                 impl-result
                 ;; Implementer reported success but curator still returned
                 ;; something non-success (shouldn't happen outside no-files;
                 ;; defensive fallback).
                 :else
                 curator-result)]
    (-> (phase/enter-context ctx :implement :implementer gates budget start-time result)
        (assoc-in [:phase :rules-manifest] rules-manifest)
        (assoc-in [:phase :watchdog-state]
                  {:stalled? stalled? :stall/gap-duration-ms gap-ms}))))

(def ^:private rate-limit-pattern
  "Lightweight rate limit / infra-transient detection for the phase level.
   Avoids a dependency on workflow/dag-resilience.

   Widened 2026-04-17 after a dogfood failure where the LLM backend
   returned near-empty content in ~4s (quota-exhausted-adjacent infra
   failure) that the prior narrow regex missed. Added: HTTP 503, 'too
   many requests', 'overloaded', 'try again', 'capacity', 'throttled',
   and a broader apostrophe class for 'you've'."
  #"(?i)you['\u2019]ve hit your (usage |)limit|rate.?limit|429|503|quota.?exceeded|resets? \d+[ap]m|too many requests|overloaded|try again (?:later|in)|at capacity|throttled")

(def ^:private suspicious-short-duration-ms
  "Threshold below which an implement phase is too fast to have done real
   work. A legitimate implementer LLM call takes minutes; anything under
   30s that ALSO failed to produce files is almost always an infra
   failure (auth, quota, transient backend error) rather than a real
   task failure. Routing these through the rate-limit branch lets the
   workflow pause and resume rather than terminating permanently."
  30000)

(defn- suspicious-short-termination?
  "Heuristic: implement phase completed in <30s AND produced no files.
   Observed 2026-04-17 dogfood pattern — LLM stream returned immediately
   with empty content; agent-status :error but no rate-limit keywords in
   the error message. Almost certainly an infra failure that masqueraded
   as a task failure."
  [result]
  (let [duration (get-in result [:metrics :duration-ms] 0)
        error-code (get-in result [:error :data :code])
        no-files? (= :curator/no-files-written error-code)]
    (and no-files?
         (pos? duration)
         (< duration suspicious-short-duration-ms))))

(defn- rate-limit-in-result?
  "Check if an agent result indicates an infrastructure failure that
   should pause/resume rather than terminate. Two signals:
     1. Known rate-limit / quota / transient-backend keywords in error text.
     2. Suspiciously short termination with no files produced (observed
        symptom of LLM-backend failure that returned empty content fast)."
  [result]
  (or (some (fn [text]
              (and (string? text) (re-find rate-limit-pattern text)))
            [(get-in result [:error :message])
             (when (string? (:output result)) (:output result))])
      (suspicious-short-termination? result)))

(defn- network-drop-in-result?
  "True when the agent result carries a network-drop signal from
   PR-B's `network-monitor`. Three locations may carry it:

   1. `[:error :data :timeout :type]` — the canonical agent result
      shape. `streaming-error-response` builds an llm-error map with
      `:timeout` on the `:error`; `result-boundary/error-response`
      then merges that map into `[:error :data]` via `response/error`.
      This is the path the implement phase actually sees from a
      production network-drop.
   2. `[:timeout :type]` — back-compat for raw exec-result maps that
      bypass the result-boundary wrapping (test harnesses, direct
      stream-exec-fn captures).
   3. `[:error :message]` text containing the formatted
      `Adaptive timeout: ... (type: network-drop, ...)` produced by
      `format-timeout-error` — defensive fallback for paths that
      surface the timeout via the error channel as a plain string.

   The message-channel regex anchors on the explicit `type:
   network-drop` marker emitted by `format-timeout-error` so an
   unrelated message that happens to mention 'network drop' in prose
   does not trip the predicate.

   Distinct from `rate-limit-in-result?` — the network monitor detects
   raw TCP/TLS reachability loss (no provider response at all), while
   rate-limit detection keys off provider-emitted 429/quota messages.
   The two anomalies want different recovery strategies: rate-limit
   pauses + waits; network-drop retries from the last persisted
   checkpoint (PR-C of the network-resilience stack)."
  [result]
  (or (= :network-drop (get-in result [:error :data :timeout :type]))
      (= :network-drop (get-in result [:timeout :type]))
      (let [msg (get-in result [:error :message])]
        (and (string? msg)
             (re-find #"(?i)type:\s*network-drop" msg)))))

(defn- backend-timeout-in-result?
  "True when the agent result carries an adaptive-timeout signal that
   is NOT `:network-drop` — i.e. the LLM call hit a stream-idle,
   stagnation, or hard-limit timeout. Mirrors
   `network-drop-in-result?`'s three-location path priority so the
   two timeout families stay symmetrically shaped (matching the
   2026-06-06 phase-timeout PR-A primitives in result-boundary).

   The 2026-06-05 dogfood (`adhoc-944448986`) revealed the gap: the
   reviewer LLM stream-idle'd at 360s and the workflow had no
   per-phase verdict to distinguish that infra timeout from a real
   defect. PR-A/B fixed the review-phase side; this is the implement-
   phase parity.

   Distinct from `network-drop-in-result?` — the network monitor
   detects TCP/TLS reachability loss; this catches the cases where
   the provider stayed connected but stopped producing output (or
   ran past a hard wall-clock cutoff). The two arms never both fire
   on the same result because the `:type` is mutually exclusive.

   Distinct from `rate-limit-in-result?` — that keys off provider-
   emitted 429/quota messages; this keys off the LLM client's own
   adaptive-timeout taxonomy."
  [result]
  (let [timeout-types #{:stream-idle :stagnation :hard-limit}
        boundary-type (or (get-in result [:error :data :timeout :type])
                          (get-in result [:timeout :type]))]
    (or (contains? timeout-types boundary-type)
        (let [msg (get-in result [:error :message])]
          (and (string? msg)
               (re-find #"(?i)type:\s*(stream-idle|stagnation|hard-limit)" msg))))))

(defn- extract-error-message
  "Extract the most relevant error message from an agent result."
  [result default-msg]
  (or (not-empty (get-in result [:error :message]))
      (not-empty (get-in result [:output :error]))
      default-msg))

(defn- prior-verify-failure?
  "Return true when the previous verify phase recorded a failure."
  [ctx]
  (let [verify-phase (get-in ctx [:execution/phase-results :verify])]
    (contains? #{:failed :failure :error}
               (or (:status verify-phase)
                   (get-in verify-phase [:result :status])))))

(defn- unverified-already-implemented?
  "Return true when implement claims :already-implemented while responding to
   known review or verify failures."
  [ctx result]
  (and (= :already-implemented (:status result))
       (or (resolve-review-feedback ctx)
           (prior-verify-failure? ctx))))

(defn- build-unverified-already-implemented-error
  "Build a structured error for an unsupported :already-implemented repair outcome."
  [ctx result iterations]
  {:message (messages/t :implement/unverified-already-implemented)
   :agent-status (:status result)
   :rate-limited? false
   :iterations iterations
   :review-feedback? (boolean (resolve-review-feedback ctx))
   :verify-failure? (prior-verify-failure? ctx)})

(defn- build-phase-error
  "Build a structured phase error map from an agent result."
  [result agent-status rate-limited? iterations]
  {:message (or (extract-error-message result nil)
                (when (string? (:output result))
                  (not-empty (:output result)))
                (messages/t :implement/exhausted-retries))
   :agent-status agent-status
   :rate-limited? (boolean rate-limited?)
   :iterations iterations})

(defn- successful-curated-artifact
  "Extract the curated artifact from a successful implement phase result."
  [result]
  (or (:artifact result)
      (:output result)))

(defn- lightweight-curated-artifact
  "Persist only lightweight curated metadata in phase results.
   Serialized code stays in the worktree and can be rehydrated later."
  [artifact]
  (when artifact
    (let [files (:code/files artifact)]
      (cond-> (dissoc artifact :code/files)
        (seq files)
        (assoc :code/file-paths (mapv :path files)
               :code/file-actions (mapv :action files)
               :code/file-count (count files))))))

(defn leave-implement
  "Post-processing for implementation phase.

   Records metrics, captures code artifacts."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (if start-time (- end-time start-time) 0)
        result (get-in ctx [:phase :result])
        agent-status (:status result)
        rate-limited? (and (= :error agent-status) (rate-limit-in-result? result))
        ;; PR-C of network-resilience stack: PR-B's network-monitor
        ;; flagged a sustained provider outage. Recovery is the
        ;; opposite of rate-limited — burn an iteration to retry from
        ;; the persisted checkpoint rather than pause/wait, since the
        ;; subprocess is already dead and the LLM call's state was
        ;; mid-stream when the drop hit.
        network-dropped? (and (= :error agent-status)
                              (network-drop-in-result? result))
        ;; PR-C of phase-timeout stack: companion to network-dropped?.
        ;; Stream-idle / stagnation / hard-limit timeouts from the
        ;; implementer LLM call. Retriable from the persisted
        ;; checkpoint up to the iteration budget (same recovery shape
        ;; as network-dropped); after budget exhaustion the verdict
        ;; below escalates to `:implement/backend-timeout` terminal.
        ;; Two arms never both fire on the same result — the
        ;; underlying `:timeout :type` is mutually exclusive.
        backend-timeout? (and (= :error agent-status)
                              (backend-timeout-in-result? result))
        gate-failed? (= :failed (:phase/status (get-in ctx [:phase])))
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
        invalid-already-implemented? (unverified-already-implemented? ctx result)
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
        metrics (get result :metrics {:tokens 0 :duration-ms duration-ms})
        cost-usd (get result :cost-usd
                   (get metrics :cost-usd
                     (* (get metrics :tokens 0) 0.000015)))
        metrics (assoc metrics :cost-usd cost-usd :duration-ms duration-ms)
        env-id (get ctx :execution/environment-id)
        curated-artifact (successful-curated-artifact result)
        degraded-handoff? (true? (:degraded-handoff? result))
        raw-agent-status (or (:raw-agent-status result) agent-status)
        summary (or (get-in result [:output :code/summary])
                    (when (string? (:output result)) (:output result))
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
                        (update-in [:execution/metrics :cost-usd] (fnil + 0.0) cost-usd)
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
            (-> (assoc-in [:phase :result] (phase/success env-id summary))
                (assoc-in [:phase :artifact]
                          (cond-> (lightweight-curated-artifact curated-artifact)
                            degraded-handoff?
                            (assoc :code/degraded-handoff? true
                                   :code/raw-agent-status raw-agent-status
                                   :code/raw-error (:raw-error result)))))
            (phase/retrying? (:phase updated-ctx))
            (-> (update-in [:phase :iterations] (fnil inc 1))
                (assoc-in [:phase :last-error]
                          (extract-error-message result (messages/t :implement/agent-error))))

            (= :failed phase-status)
            (assoc-in [:phase :error]
                      (if invalid-already-implemented?
                        (build-unverified-already-implemented-error ctx result iterations)
                        (build-phase-error result agent-status rate-limited? iterations)))

            (and (= :already-implemented agent-status)
                 (not invalid-already-implemented?))
            (assoc-in [:phase :skipped-reason] :already-implemented))]
      ;; Emit phase-completed telemetry with termination reason
      (phase/emit-phase-completed! final-ctx :implement
        (merge {:outcome     (if (contains? #{:completed :already-implemented} phase-status)
                               :success
                               :failure)
                :duration-ms duration-ms
                :tokens      (get metrics :tokens 0)}
               (phase-terminal/derive-termination-reason
                 result (merge watchdog-state {:rate-limited? rate-limited?}))))
      final-ctx)))

(defn error-implement
  "Handle implementation phase errors. Attempts repair via inner loop
   within budget; on exhaustion redirects via `:on-fail` when set,
   otherwise propagates. Delegates to the shared `phase/handle-error`
   helper."
  [ctx ex]
  (phase/handle-error ctx ex 5))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod phase/get-phase-interceptor-method :implement
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::implement
     :config merged
     :enter (fn [ctx]
              (enter-implement (assoc ctx :phase-config merged)))
     :leave leave-implement
     :error error-implement}))

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
