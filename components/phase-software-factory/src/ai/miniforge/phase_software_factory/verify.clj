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
(ns ai.miniforge.phase-software-factory.verify
  "Verification phase interceptor.

   Runs the test suite directly inside the executor environment.
   No tester agent: the implementer wrote both source and test files;
   this phase validates them by running the test suite.
   Default gates: [:tests-pass :coverage]"
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase.interface.gate :as phase-gate]
            [ai.miniforge.workspace.interface :as workspace]
            [ai.miniforge.phase-software-factory.gap-wiring :as gap-wiring]
            [ai.miniforge.phase-software-factory.phase-config :as phase-config]
            [ai.miniforge.phase-software-factory.messages :as messages]
            [ai.miniforge.phase-software-factory.phase-terminal :as phase-terminal]
            [ai.miniforge.phase-software-factory.verify.output :as output]
            [ai.miniforge.phase-software-factory.verify.result :as result]
            [ai.miniforge.phase-software-factory.verify.registration :as registration]
            [babashka.process :as process]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} parse-test-output
  "Parse Clojure or Cargo summaries; the runner's exit code remains authoritative."
  output/parse-test-output)

(def ^{:stratum 0} default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :verify))

(def ^{:stratum 0} ^:private timeout-message-fragment
  "Substring that marks a non-actionable verify timeout.
   Produced by `run-tests!` when the test command exceeds its deadline,
   and matched by `leave-verify` to skip the redirect-to-implement path
   (retrying implement won't fix a stalled test process)."
  "timed out")

(def ^{:stratum 0} default-test-timeout-ms
  "Default wall-clock budget for `run-tests!` before the test process is
   destroyed. Picked so an absent override still bounds the verify phase:
   30 min covers slow Polylith suites + CI cold cache, but stops the
   ~indefinite hangs seen when bb test blocks on Docker acquire or stdin.
   Override per spec via :spec/test-timeout-ms or per phase-config."
  (* 30 60 1000))

(def ^{:stratum 0} ^:private verify-rate-limit-pattern
  "Pattern for non-actionable verify failures caused by provider throttling."
  #"(?i)rate.?limit|429|you've hit your limit|quota.?exceeded")

(defn- ^{:stratum 0} test-error-result
  "Build a test-results map representing an error (exception, unparseable output, etc.)."
  [message]
  (result/test-error-result message))

(defn ^{:stratum 0} infer-test-command
  "Infer the test command from the repo structure.
   Checks for Cargo.toml (Rust) and package.json (JS), in that order.
   Falls back to 'bb test' (Clojure, or when neither marker is present)."
  [worktree-path]
  (cond
    (fs/exists? (fs/path worktree-path "Cargo.toml")) "cargo test --workspace"
    (fs/exists? (fs/path worktree-path "package.json")) "npm test"
    :else "bb test"))

(defn- ^{:stratum 0} destroy-process-tree!
  "Forcefully kill `proc` and all of its descendants.

   `.destroyForcibly` on a parent `sh -c ...` does NOT propagate to children,
   so the JVM running `bb test` is left orphaned after we time out. Walks the
   descendant tree explicitly (children first, then the parent) so we don't
   leak processes or hold file/port locks across verify runs."
  [^Process proc]
  (try
    (doseq [^java.lang.ProcessHandle child (.. proc descendants (toArray))]
      (try (.destroyForcibly child) (catch Throwable _)))
    (catch Throwable _))
  (try (.destroyForcibly proc) (catch Throwable _)))

(defn- ^{:stratum 0} require-environment-result
  "Return nil when the verify execution environment exists, otherwise return a
   canonical anomaly describing the fail-closed phase entry error."
  [ctx]
  (when-not (get ctx :execution/environment-id)
    (anomaly/sub-anomaly :invalid-input
                         :anomalies.phase/enter-failed
                         (messages/t :verify/no-environment)
                         {:phase :verify
                          :hint (messages/t :verify/no-environment-hint)})))

(defn- ^{:stratum 0} compute-verdict
  "Pick the verdict that should flow on the phase result.

   `:verify/timeout` and `:verify/rate-limited` take priority over
   `:repair-requested` — those failure modes are not code-quality
   issues, so retrying implement is wasted work (this was the
   2026-05-28 dogfood's bottleneck: verify hit a 30-min timeout, the
   loop redirected to implement, implement wrote a recovery, verify
   timed out AGAIN, repeat. The FSM's `:verdict/terminal?` guard now
   short-circuits the loop)."
  [{:keys [phase-failed? on-fail-configured? timeout? rate-limited?]}]
  (cond
    timeout?
    :verify/timeout

    rate-limited?
    :verify/rate-limited

    (and phase-failed? on-fail-configured?)
    :repair-requested

    phase-failed?
    :exhausted

    :else
    :approved))

(defn- ^{:stratum 0} attach-verify-error
  "Attach the verify error map to the phase result for the evidence
   bundle. Carries the legacy `:timeout?` / `:rate-limited?` flag bits
   too so consumers reading those keys keep working until Phase 4
   removes them."
  [ctx error-message agent-status timeout? rate-limited? gate-failed?]
  (assoc-in ctx [:phase :error]
            {:message       error-message
             :agent-status  agent-status
             :timeout?      timeout?
             :rate-limited? rate-limited?
             :gate-failed?  gate-failed?}))

(defn ^{:stratum 0} error-verify
  "Handle verification phase errors. Retries within budget; on
   exhaustion redirects via `:on-fail` (typically to `:implement`)
   when set, otherwise propagates as a failed phase. Delegates to the
   shared `phase/handle-error` helper."
  [ctx ex]
  (phase/handle-error ctx ex 3))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} require-environment!
  "Boundary wrapper around the anomaly-returning
   `require-environment-result`; retained for existing exception callers."
  [ctx]
  (when-let [result (require-environment-result ctx)]
    (throw (ex-info (:anomaly/message result)
                    (:anomaly/data result)))))

(defn ^{:stratum 1} leave-verify
  "Post-processing for verification phase.

   Records test metrics: count, pass rate, coverage.

   Phase 3 (Phase 2b shape generalized to verify): when verification
   fails, attaches a `:phase/verdict` to the result so the FSM's
   verdict-driven guarded `:phase/fail` array (see
   `workflow/fsm/build-phase-state` and
   `workflow/standard-guards-and-actions`) decides between an on-fail
   redirect and a terminal `:failed`. No more `phase/request-redirect`
   — the FSM owns routing.

   `:verify/timeout` and `:verify/rate-limited` are TERMINAL verdicts:
   the FSM bypasses on-fail and lands on `:failed` because retrying
   the implement target wouldn't help (a hung test process is a
   process issue; a provider rate-limit is a quota issue; implementing
   different code doesn't fix either)."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (if start-time (- end-time start-time) 0)
        result (get-in ctx [:phase :result])
        agent-status (:status result)
        gate-failed? (phase-gate/gate-failed? (:phase ctx))
        parse-error? (true? (get-in result [:metrics :parse-error?]))
        timeout? (and (= :error agent-status)
                      (not parse-error?)
                      (some-> (get-in result [:error :message])
                              (str/includes? timeout-message-fragment)))
        rate-limited? (and (= :error agent-status)
                           (not parse-error?)
                           (let [msg (str (get-in result [:error :message]))]
                             (re-find verify-rate-limit-pattern msg)))
        phase-status (cond
                       gate-failed?                :failed
                       (= :error agent-status)     :failed
                       :else                       :completed)
        metrics (-> (get result :metrics {:tokens 0 :duration-ms duration-ms})
                    (assoc :duration-ms duration-ms))
        iterations (get-in ctx [:phase :iterations] 1)
        on-fail (get-in ctx [:phase-config :on-fail])
        error-message (or (not-empty (get-in result [:error :message]))
                          (when gate-failed? (messages/t :verify/gate-failed))
                          (messages/t :verify/failed))
        verdict (compute-verdict {:phase-failed?       (= :failed phase-status)
                                  :on-fail-configured? (some? on-fail)
                                  :timeout?            timeout?
                                  :rate-limited?       rate-limited?})
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] phase-status)
                        (assoc-in [:phase :metrics] metrics)
                        (assoc-in [:phase :verdict] verdict)
                        (assoc-in [:phase :result :output :phase/verdict] verdict)
                        (assoc-in [:metrics :verification :duration-ms] duration-ms)
                        (assoc-in [:metrics :verification :repair-cycles] (dec iterations))
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :cost-usd] (fnil + 0.0) (:cost-usd metrics 0.0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))
        next-ctx (cond
                   (= :completed phase-status)
                   (update-in updated-ctx [:execution :phases-completed] (fnil conj []) :verify)

                   (= :failed phase-status)
                   (attach-verify-error updated-ctx error-message agent-status
                                        timeout? rate-limited? gate-failed?)

                   :else
                   updated-ctx)]
    ;; Gap-instrument miss recording (T2 s3): best-effort, opt-in, and it
    ;; must never change the outcome it measures. On next-ctx, not ctx:
    ;; gate failures sit on the untouched NAMESPACED [:phase :phase/...]
    ;; keys either way, but the run-tests-error shape only gains its
    ;; [:phase :error] map in attach-verify-error above. Verify has no
    ;; static situation mapping, so its misses classify :uncovered —
    ;; honest: no codex board addresses a failing-verify loop yet.
    (gap-wiring/record-phase-misses! next-ctx :verify)
    (phase/emit-phase-completed! next-ctx :verify
      (merge {:outcome     (phase/outcome result (= :completed phase-status))
              :duration-ms duration-ms
              :tokens      (get metrics :tokens 0)}
             (phase-terminal/derive-termination-reason result
               {:stalled?      timeout?
                :rate-limited? rate-limited?})))
    next-ctx))

(defn ^{:stratum 1} run-tests!
  "Run tests in the worktree. Infers the test command from the repo structure
   unless test-cmd is supplied explicitly.

   Bounded by :timeout-ms (default `default-test-timeout-ms`). On timeout
   the test process and its children are destroyed forcibly and a failed
   result is returned whose :output contains `timeout-message-fragment` so
   leave-verify routes it as a non-actionable verify failure rather than
   redirecting back to implement.

   When the calling thread is interrupted the test process tree is
   destroyed too, the interrupt flag is restored, and a failed result
   carrying `:interrupted? true` is returned.

   Returns parsed test results map."
  [worktree-path & {:keys [test-cmd timeout-ms]}]
  (let [cmd        (or test-cmd (infer-test-command worktree-path))
        timeout-ms (or timeout-ms default-test-timeout-ms)]
    (try
      ;; process/process gives us a Process handle so we can apply our own
      ;; deadline; process/shell only returns after the child exits and
      ;; would block here indefinitely on a hung test command.
      (let [proc     (process/process
                       {:dir (str worktree-path)
                        :out :string :err :string :continue true}
                       "sh" "-c" cmd)
            done-fut (future @proc)]
        ;; Plain `try`: this component has no slingshot dependency
        ;; (rule 211 exemption a).
        (try
          (if (not= ::timeout (deref done-fut timeout-ms ::timeout))
            (let [result @done-fut]
              (parse-test-output (str (:out result "") "\n" (:err result ""))
                                 (:exit result 1)))
            (do
              ;; .destroyForcibly on the `sh` parent does NOT reliably kill the
              ;; test runner child (the actual JVM running `bb test`). Walk the
              ;; descendant tree first so children die before the shell does;
              ;; this is what produced the orphan polylith poly-cli process
              ;; observed on 2026-05-18.
              (destroy-process-tree! (:proc proc))
              ;; Bounded wait for the reader threads to drain; if they don't,
              ;; cancel the future so the thread doesn't leak across verify runs.
              (when (= ::timeout (deref done-fut 5000 ::timeout))
                (future-cancel done-fut))
              (assoc (test-error-result
                       (messages/t :verify/timed-out
                                   {:timeout-ms timeout-ms :test-cmd cmd}))
                     :timed-out? true)))
          (catch InterruptedException _
            ;; The phase thread was interrupted mid-run. Kill the test
            ;; process tree (it would keep running against the worktree),
            ;; release the waiting future, then restore the interrupt flag
            ;; the exception cleared so the caller still sees it.
            (destroy-process-tree! (:proc proc))
            (future-cancel done-fut)
            (.interrupt (Thread/currentThread))
            (assoc (test-error-result
                     (messages/t :verify/interrupted {:test-cmd cmd}))
                   :interrupted? true))))
      (catch Exception e
        (test-error-result (.getMessage e))))))

(defn ^{:stratum 1} run-tests-in-capsule!
  "Run tests inside a task capsule via execute-fn (N11 §6).
   Routes the test command through the executor instead of host process/shell.
   execute-fn is dag-exec/execute! passed through context to avoid cross-component requires.

   Bounded by :timeout-ms (default `default-test-timeout-ms`) — passed
   through to the capsule executor's `execute!` protocol which honours
   the option per `dag-executor.protocols.executor/TaskExecutor`. Without
   this bound a stuck `bb test` inside the capsule produced the same
   silent verify hang seen on the host path."
  [execute-fn executor env-id worktree-path & {:keys [test-cmd timeout-ms]}]
  (let [cmd        (or test-cmd "bb test")
        timeout-ms (or timeout-ms default-test-timeout-ms)]
    (try
      (let [result (execute-fn executor env-id cmd
                               {:workdir worktree-path
                                :timeout-ms timeout-ms})
            data   (:data result)
            exit   (get data :exit-code 1)
            parsed (parse-test-output (str (get data :stdout "") "\n" (get data :stderr ""))
                                      exit)]
        ;; Executors signal timeout via :timed-out? (or a sentinel exit) on
        ;; the result. Surface it on the parsed map so leave-verify routes
        ;; through the non-actionable path and doesn't redirect to implement.
        (if (or (:timed-out? result)
                (:timed-out? data))
          (assoc parsed
                 :passed? false
                 :timed-out? true
                 :output (messages/t :verify/timed-out
                                     {:timeout-ms timeout-ms :test-cmd cmd}))
          parsed))
      (catch Exception e
        (test-error-result (.getMessage e))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} enter-verify
  "Execute verification phase.

   Runs the test suite directly inside the executor environment.
   No tester agent — the implementer already wrote test files.
   Fails fast if no execution environment is present in context."
  [ctx]
  (let [config (phase/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Emit phase-started telemetry event
        _ (phase/emit-phase-started! ctx :verify)

        ;; Fail fast if no executor environment has been acquired
        _ (require-environment! ctx)

        worktree-path (workspace/resolve-execution-workdir ctx "verify")

        ;; Allow spec to override the test command; otherwise infer from repo structure
        test-cmd (or (get-in ctx [:execution/input :spec/test-command])
                     (get-in ctx [:execution/input :test-command]))

        ;; Wall-clock deadline for the test process. Spec wins, then
        ;; phase-config, then a phase-level default. Without this bound
        ;; a hung `bb test` (Docker acquire, stdin block, infinite loop)
        ;; freezes the verify phase indefinitely with zero events —
        ;; observed 2026-05-18 on workflow aadac7ce.
        timeout-ms (or (get-in ctx [:execution/input :spec/test-timeout-ms])
                       (get-in ctx [:execution/input :test-timeout-ms])
                       (get config :timeout-ms)
                       default-test-timeout-ms)

        ;; Run the test suite — inside capsule for governed mode, on host otherwise
        execute-fn (get ctx :execution/execute-fn)
        test-results (if (and (= :governed (get ctx :execution/mode))
                              execute-fn
                              (get ctx :execution/executor))
                       (run-tests-in-capsule! execute-fn
                                              (get ctx :execution/executor)
                                              (get ctx :execution/environment-id)
                                              worktree-path
                                              :test-cmd test-cmd
                                              :timeout-ms timeout-ms)
                       (run-tests! worktree-path
                                   :test-cmd test-cmd
                                   :timeout-ms timeout-ms))

        ;; Phase result carries environment reference and test metrics (N6 environment model).
        ;; No serialized code — changes live in the environment's worktree.
        ;; :metrics carries pass/fail counts and test-output for the evidence bundle.
        env-id     (get ctx :execution/environment-id)
        passed?    (:passed? test-results)
        pass-count (get test-results :test-count 0)
        raw-fails  (get test-results :fail-count 0)
        raw-errors (get test-results :error-count 0)
        metrics    (cond-> (phase/test-metrics pass-count
                                                (+ raw-fails raw-errors)
                                                (get test-results :output ""))
                     (:parse-error? test-results)
                     (assoc :parse-error? true)
                     (seq (:failures test-results))
                     (assoc :failures (:failures test-results)))
        summary    (if passed?
                     (messages/t :verify/tests-passed {:pass-count pass-count})
                     (output/verify-failure-message test-results raw-fails raw-errors))
        result     (if passed?
                     (phase/success env-id summary metrics)
                     (phase/error   env-id summary summary metrics))]

    (phase/enter-context ctx :verify nil gates budget start-time result)))

(registration/register! ::verify default-config
                        (registration/callbacks #'enter-verify #'leave-verify #'error-verify))

(comment
  (phase/get-phase-interceptor {:phase :verify})
  (phase/phase-defaults :verify))
