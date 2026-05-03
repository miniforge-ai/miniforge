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
  (:require            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-software-factory.messages :as messages]
            
            [ai.miniforge.phase-software-factory.phase-config :as phase-config]
            [ai.miniforge.phase-software-factory.knowledge-helpers :as kb-helpers]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.context-pack.interface :as context-pack]
            [ai.miniforge.knowledge.interface :as knowledge]
            [ai.miniforge.repo-index.interface :as repo-index]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.response.interface :as response]
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
        behavior-addendum (phase/load-and-filter-behaviors
                            :implement {:task {:task/intent (:intent input)}})
        review-feedback (resolve-review-feedback ctx)
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
        agent-ctx (cond-> ctx on-chunk (assoc :on-chunk on-chunk))
        peer-advice (collect-peer-advice ctx)
        task (cond-> task
               peer-advice (assoc :task/peer-advice peer-advice))
        impl-result (try
                      (agent/invoke implementer-agent task agent-ctx)
                      (catch Exception e
                        (response/failure e)))
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
        impl-succeeded? (phase/result-succeeded? impl-result)
        ;; "Degraded handoff" means the implementer agent reported a hard
        ;; error but the curator recovered files anyway. :already-implemented
        ;; is a deliberate skip (work was completed in a prior turn) — the
        ;; agent is correctly reporting success-by-prior-work, NOT failing.
        ;; Don't mark such handoffs as degraded; the reviewer's handoff
        ;; gate would otherwise auto-reject every repair iteration that
        ;; legitimately recognized "already done."
        impl-errored? (= :error (:status impl-result))
        result (cond
                 ;; Curator found files — use its artifact, even if the
                 ;; implementer reported error. Merge implementer metrics through.
                 (phase/result-succeeded? curator-result)
                 (-> impl-result
                     (assoc :status :success
                            :success? true)
                     (assoc :output (:output curator-result))
                     (cond-> impl-errored?
                       (assoc :degraded-handoff? true
                              :raw-agent-status (:status impl-result)
                              :raw-error (:error impl-result)))
                     (dissoc :error)
                     (update :metrics merge (:metrics curator-result)))
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
        (assoc-in [:phase :rules-manifest] rules-manifest))))

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
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        agent-status (:status result)
        rate-limited? (and (= :error agent-status) (rate-limit-in-result? result))
        gate-failed? (= :failed (:phase/status (get-in ctx [:phase])))
        ;; Curator's empty-diff verdict: the implementer wrote no files to the
        ;; environment. Retrying with the same prompt won't change that — the
        ;; next attempt would just burn another 10+ minutes producing nothing.
        ;; This is the hotfix that makes M0a's signal actually stop the burn.
        ;; TODO: M1 subsumes this into a proper FSM where terminal error codes
        ;; are a first-class concept.
        curator-empty-diff? (= :curator/no-files-written
                               (get-in result [:error :data :code]))
        iterations (get-in ctx [:phase :iterations] 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        ;; In the environment promotion model the agent writes files directly to
        ;; the executor environment. A nil output is NOT an error — code is in
        ;; the environment, not serialized in the result.
        effective-status agent-status
        invalid-already-implemented? (unverified-already-implemented? ctx result)
        phase-status (cond
                       gate-failed? :failed
                       invalid-already-implemented? :failed
                       (= :already-implemented agent-status) :already-implemented
                       ;; Rate limit: fail immediately, don't burn retry budget
                       rate-limited? :failed
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
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] phase-status)
                        (assoc-in [:phase :metrics] metrics)
                        (assoc-in [:metrics :implementation :duration-ms] duration-ms)
                        (assoc-in [:metrics :implementation :repair-cycles] (dec iterations))
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :cost-usd] (fnil + 0.0) cost-usd)
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Capture inner-loop learning when repair succeeded (iterations > 1, completed)
    (when (= :completed phase-status)
      (knowledge/capture-repair-learning!
       (:knowledge-store ctx) :implementer
       (get-in ctx [:execution/input :title]) iterations))
    ;; Handle retrying, failure, completed, or already-implemented outcomes
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
      (assoc-in [:phase :skipped-reason] :already-implemented))))

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
