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
(ns ai.miniforge.phase-software-factory.release
  "Release phase interceptor.

   Prepares release artifacts and creates PRs using the release-executor component.
   All git/gh operations route through the DAG executor so governed-mode capsules
   never shell out to the host. The GitHub token is resolved and injected into the
   capsule as GH_TOKEN for gh CLI authentication.
   Agent: :releaser
   Default gates: [:release-ready]"
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase.interface.gate :as phase-gate]
            [ai.miniforge.phase-software-factory.codex-pin :as codex-pin]
            [ai.miniforge.phase-software-factory.gap-wiring :as gap-wiring]
            [ai.miniforge.phase-software-factory.messages :as messages]
            [ai.miniforge.phase-software-factory.phase-config :as phase-config]
            [ai.miniforge.phase-software-factory.phase-terminal :as phase-terminal]
            [ai.miniforge.phase-software-factory.release-publish :as release-publish]
            [ai.miniforge.phase-software-factory.release-state :as release-state]
            [ai.miniforge.release-executor.interface :as release-executor]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} attach-consultation
  "Record `codex-outcome`'s summary onto `result`, on both success and
   failure -- :output starts nil on response/failure, so a failed
   release that consulted must not be ledgered as one that never did.
   §7.7.2: the result carries the releaser session's recorded peg
   answers and the lost-vs-unanswered answer-log marker (threaded up
   from the release executor) -- hand both to the summary."
  [result codex-outcome]
  (cond-> result
    (map? result)
    (assoc-in [:output :codex/consultation]
              (codex-pin/consultation-summary codex-outcome nil
                                              (:codex-answers result)
                                              (:codex-answer-log result)))))

;; Defaults
(def ^{:stratum 0} default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :release))

(defn- ^{:stratum 0} compute-verdict
  "Pick the verdict that should flow on the phase result.

   `:release/zero-files` pre-empts `:repair-requested` — when the curator
   reports an empty diff, retrying the implementer is wasted work: the
   next pass would just produce another empty diff. Make this FSM-visible
   so the verdict-driven guarded array terminates the loop."
  [{:keys [phase-failed? on-fail-configured? zero-files?]}]
  (cond
    zero-files?
    :release/zero-files

    (and phase-failed? on-fail-configured?)
    :repair-requested

    phase-failed?
    :exhausted

    :else
    :approved))

(defn- ^{:stratum 0} attach-error-verdict
  "Phase 3b: when error-release fails terminally, attach the verdict to
   the result so the FSM's guarded :phase/fail array reads it. The
   `:release/zero-files` case is terminal; everything else is either
   :repair-requested (on-fail set, FSM will redirect) or :exhausted
   (no on-fail target)."
  [ctx zero-files? on-fail-configured?]
  (let [verdict (cond
                  zero-files?            :release/zero-files
                  on-fail-configured?    :repair-requested
                  :else                  :exhausted)]
    (-> ctx
        (assoc-in [:phase :verdict] verdict)
        (assoc-in [:phase :result :output :phase/verdict] verdict))))

(defn- ^{:stratum 0} release-file-paths
  "The distinct paths of the files `workflow-state` releases."
  [workflow-state]
  (into []
        (comp (mapcat (comp :code/files :artifact/content)) (keep :path) (distinct))
        (:workflow/artifacts workflow-state)))

(defn ^{:stratum 0} build-executor-context
  "Build context for the release executor from phase context: the
   publish context (release-publish/build-publish-context) plus what the
   releaser agent needs.

   Also computes the phase-filtered policy-pack behavior addendum via
   `phase/load-and-filter-behaviors :release` and threads it as
   `:task/behavior-addendum` so `release-executor/invoke-releaser` can
   forward it into the agent input. Mirrors the wiring review (#945)
   and plan use; closes the gap where the releaser ran without the
   compiled standards pack. Rules are filtered by `changed-paths`, the
   files being released, so glob-scoped rules apply."
  [ctx config logger changed-paths]
  (let [on-chunk (phase/create-streaming-callback ctx :release)
        input (get-in ctx [:execution/input])
        ;; Codex consultation for the release situation, delivered through
        ;; the same addendum channel as the compiled standards pack — the
        ;; releaser has no existing-files channel (SPEC T1 s7.4). The
        ;; outcome rides on the exec-context so enter-release can attach
        ;; provenance without consulting twice.
        codex-outcome (codex-pin/landings-outcome :release logger)
        pack-addendum (phase/load-and-filter-behaviors
                        :release {:task     {:task/intent (:intent input)}
                                  :artifact {:artifact/paths changed-paths}})
        behavior-addendum (let [t (:text codex-outcome)]
                            (cond
                              (and pack-addendum t) (str pack-addendum "\n\n" t)
                              t t
                              :else pack-addendum))]
    (cond-> (assoc (release-publish/build-publish-context ctx config logger)
                   :llm-backend (get-in ctx [:execution/llm-backend]))
      on-chunk (assoc :on-chunk on-chunk)
      behavior-addendum (assoc :task/behavior-addendum behavior-addendum)
      ;; :text already rode into the behavior addendum; the summary only
      ;; needs the status fields — don't ship the rendered prose twice.
      codex-outcome (assoc :codex/outcome (dissoc codex-outcome :text)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} leave-release
  "Post-processing for release phase.

   Publishes what enter-release prepared (push and PR) unless a gate
   failed, then records release metrics and PR info.

   Phase 3b: attaches a `:phase/verdict` to the phase result so the
   FSM's verdict-driven guarded `:phase/fail` array picks the right
   target. `:release/zero-files` is a TERMINAL verdict — the FSM
   bypasses `:on-fail` and lands on `:failed` because the curator's
   empty-diff signal means the implementer has nothing left to do."
  [ctx]
  ;; Gap-instrument miss recording (T2 s3): best-effort, opt-in, and it
  ;; must never change the outcome it measures. Consultation provenance
  ;; was attached at enter, so it is visible here.
  (gap-wiring/record-phase-misses! ctx :release)
  (let [ctx (release-publish/publish-when-allowed ctx)
        start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (if start-time (- end-time start-time) 0)
        result (get-in ctx [:phase :result])
        release-data (when (phase/result-succeeded? result) (:output result))
        release-metrics (get release-data :release/metrics {})
        metrics (merge {:tokens 0 :duration-ms duration-ms} release-metrics)
        agent-status (:status result)
        iterations (get-in ctx [:phase :iterations] 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        zero-files? (= :release/zero-files
                       (get-in result [:error :data :type]))
        ;; A gate denial (projected onto :phase/status by the workflow's
        ;; gate validation) fails the release whatever the agent returned.
        gate-failed? (phase-gate/gate-failed? (:phase ctx))
        phase-status (if (or zero-files? gate-failed?)
                       :failed
                       (phase/determine-phase-status
                        agent-status iterations max-iterations))
        on-fail (get-in ctx [:phase-config :on-fail])
        ;; Verdict is only meaningful for terminal phase statuses
        ;; (:completed → :approved; :failed → one of the failure
        ;; verdicts). For :retrying the runner handles the in-phase
        ;; loop and the FSM never sees a transition, so skip the
        ;; verdict attachment — leaving the prior verdict in place if
        ;; the phase has been around the loop before, else nothing.
        verdict (when (contains? #{:completed :failed} phase-status)
                  (compute-verdict {:phase-failed?       (= :failed phase-status)
                                    :on-fail-configured? (some? on-fail)
                                    :zero-files?         zero-files?}))
        updated-ctx (cond-> ctx
                      true
                      (-> (assoc-in [:phase :ended-at] end-time)
                          (assoc-in [:phase :duration-ms] duration-ms)
                          (assoc-in [:phase :status] phase-status)
                          (assoc-in [:phase :metrics] metrics))
                      verdict
                      (-> (assoc-in [:phase :verdict] verdict)
                          (assoc-in [:phase :result :output :phase/verdict] verdict)))
        updated-ctx (cond-> updated-ctx
                      true
                      (-> (assoc-in [:metrics :release :duration-ms] duration-ms)
                          (assoc-in [:metrics :release :repair-cycles] (dec iterations))
                          (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                          (update-in [:execution/metrics :cost-usd] (fnil + 0.0) (:cost-usd metrics 0.0))
                          (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))

                      (= :completed phase-status)
                      (update-in [:execution :phases-completed] (fnil conj []) :release))]
    (doto (cond-> updated-ctx
            (phase/retrying? (:phase updated-ctx))
            (-> (update-in [:phase :iterations] (fnil inc 1))
                (assoc-in [:phase :last-error]
                          (get-in result [:error :message] (messages/t :release/phase-failed)))))
      (phase/emit-phase-completed! :release
        (merge {:outcome     (phase/outcome result (= :completed phase-status))
                :duration-ms duration-ms
                :tokens      (:tokens metrics 0)}
               (phase-terminal/derive-termination-reason result nil))))))

(defn ^{:stratum 1} error-release
  "Handle release phase errors.

   Phase 3b: failures no longer call `phase/fail-and-request-redirect`
   — the FSM's verdict-driven guarded :phase/fail array routes via
   `:on-fail` if configured, or to `:failed` otherwise. error-release
   stays responsible for the phase-level retry budget; once that's
   exhausted it just attaches the verdict + error and lets the FSM
   dispatch."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 2)
        on-fail (get-in ctx [:phase-config :on-fail])
        error-map (phase/exception-error ex)
        zero-files? (= :release/zero-files (get (ex-data ex) :type))]
    (cond
      zero-files?
      (-> ctx
          (assoc :phase (phase/fail-phase (:phase ctx) error-map))
          (attach-error-verdict true (some? on-fail)))

      ;; Within phase-level retry budget — retry in place. The FSM
      ;; doesn't see this as a transition.
      (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))

      ;; Out of retries — attach verdict and let the FSM dispatch
      ;; (:repair-requested when on-fail set, :exhausted otherwise).
      :else
      (-> ctx
          (assoc :phase (phase/fail-phase (:phase ctx) error-map))
          (attach-error-verdict false (some? on-fail))))))

;; Interceptor implementation
(defn ^{:stratum 1} enter-release
  "Prepare the release.

   Uses the release executor to:
   - Generate release metadata (branch name, commit message, PR title/body)
   - Create git branch
   - Write files and stage them
   - Commit changes

   and records release readiness. Pushing and creating the PR happen in
   leave-release, after the gates."
  [ctx]
  (let [config (phase/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)
        impl-status (get-in ctx [:execution/phase-results :implement :result :status])

        logger (or (get-in ctx [:execution/logger])
                   (log/create-logger {:min-level :debug :output :human}))
        ;; Emit phase-started telemetry event
        _ (phase/emit-phase-started! ctx :release)
        ;; Skip probe: scan only when an empty worktree means "nothing to release".
        probe (when (contains? #{:already-implemented :already-satisfied nil} impl-status)
                (release-state/skip-probe ctx))]
    ;; Short-circuit: skip release when there is nothing to release.
    ;; This covers:
    ;; - implement returned :already-implemented (no new code)
    ;; - plan returned :already-satisfied (DAG had 0 tasks, no implement ran)
    ;; In both cases, only skip if there are also no git changes from other phases,
    ;; neither in the worktree nor committed on the task branch beyond its base.
    ;; A failed scan is an anomaly, which never skips; build-workflow-state
    ;; reuses the probe rather than rescanning, and raises it unless
    ;; implement's recorded files carry the release.
    (if (and (vector? probe) (empty? probe))
      (-> (phase/enter-context ctx :release nil gates budget start-time
                                      (phase/skipped :already-implemented))
          (assoc-in [:phase :status] :completed))
      (let [;; Build workflow state and context
            workflow-state (release-state/build-workflow-state ctx probe)
        _ (log/debug logger :release :release/workflow-state-built
                     {:data {:artifact-count (count (:workflow/artifacts workflow-state))
                             :file-count (count (get-in (first (:workflow/artifacts workflow-state))
                                                        [:artifact/content :code/files]))}})
        exec-context (build-executor-context ctx config logger
                                             (release-file-paths workflow-state))
        releaser-agent (get config :releaser-agent)
        ;; Execute the release phase
        ;; Emit agent-started telemetry event for release executor
        _ (phase/emit-agent-started! ctx :release :releaser)
        _ (log/info logger :release :release/executor-invoked
                    {:data {:worktree-path (:worktree-path exec-context)
                            :executor (some-> (:executor exec-context) type str)
                            :environment-id (:environment-id exec-context)
                            :create-pr? (:create-pr? exec-context)
                            :releaser-agent? (some? releaser-agent)
                            :artifact-count (count (:workflow/artifacts workflow-state))}})

        ;; Prepare only (branch and commit). The push and the PR wait for
        ;; leave-release, which runs after the gates.
        result (try
                 (let [exec-result (release-executor/prepare-release
                                    workflow-state
                                    exec-context
                                    {:releaser releaser-agent})
                       r (if (:success? exec-result)
                     (let [release-artifact (first (:artifacts exec-result))]
                       (response/success
                        {:release/id (get release-artifact :artifact/id (random-uuid))
                         :release/status :prepared
                         :release/artifacts (:artifacts exec-result)
                         :release/prepared (:prepared exec-result)
                         ;; Read by the :release-ready gate.
                         :release/readiness (release-state/release-readiness ctx)
                         :release/metrics (:metrics exec-result)}))
                     ;; Execution failed — surface the executor's :errors
                     ;; so the next dogfood run can diagnose root cause from
                     ;; the event log instead of staring at a generic
                     ;; :anomalies.phase/agent-failed.
                     (do
                       (log/error logger :release :release/executor-failed
                                  {:data {:errors (:errors exec-result)
                                          :metrics (:metrics exec-result)}})
                       (response/failure
                        (messages/t :release/phase-failed)
                        {:data {:errors (:errors exec-result)
                                :metrics (:metrics exec-result)}})))]
                   ;; §7.7.2: the releaser session's recorded peg answers
                   ;; (and the lost-vs-unanswered answer-log marker) ride
                   ;; the executor result — thread them onto the phase
                   ;; result so attach-consultation can hand them to the
                   ;; consultation summary. Success and failure alike.
                   (cond-> r
                     (some? (:codex-answers exec-result))
                     (assoc :codex-answers (:codex-answers exec-result))
                     (some? (:codex-answer-log exec-result))
                     (assoc :codex-answer-log (:codex-answer-log exec-result))))
                 (catch Exception e
                   ;; The 2026-05-03 dogfood lost the actual exception here —
                   ;; the bare (response/failure e) wraps it but no log line
                   ;; ever fired, leaving the phase-completed event with
                   ;; :phase/duration-ms 0 and no actionable detail.
                   (log/error logger :release :release/executor-threw
                              {:message (ex-message e)
                               :data (cond-> {:exception/class (.getName (class e))}
                                       (ex-data e) (assoc :exception/data (ex-data e)))})
                   (response/failure e)))

        ;; Emit agent-completed telemetry event for release executor
        _ (phase/emit-agent-completed! ctx :release :releaser result)
        ;; SPEC s7.4.3: consultation provenance onto the durable result —
        ;; the gap instrument reads it at leave (same marker implement and
        ;; plan carry). Release has no recorded context-reads channel.
        result (attach-consultation result (:codex/outcome exec-context))]

    (phase/enter-context ctx :release :releaser gates budget start-time result)))))

;------------------------------------------------------------------------------ Layer 2

;; Registry methods
(defmethod ^{:stratum 2} phase/get-phase-interceptor-method :release
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::release
     :config merged
     :enter (fn [ctx]
              (enter-release (assoc ctx :phase-config merged)))
     :leave leave-release
     :error error-release}))

;; Register defaults on load
(phase/register-phase-defaults! :release default-config)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (phase/get-phase-interceptor {:phase :release})
  (phase/phase-defaults :release)
  (phase/list-phases)
  :leave-this-here)
