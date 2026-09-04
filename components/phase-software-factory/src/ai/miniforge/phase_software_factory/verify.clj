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
            [ai.miniforge.workspace.interface :as workspace]
            [ai.miniforge.phase-software-factory.gap-wiring :as gap-wiring]
            [ai.miniforge.phase-software-factory.phase-config :as phase-config]
            [ai.miniforge.phase-software-factory.messages :as messages]
            [ai.miniforge.phase-software-factory.phase-terminal :as phase-terminal]
            [babashka.process :as process]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Defaults
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

(def ^{:stratum 0} ^:private verify-error-preview-limit
  "Maximum test-runner output included in the verify phase error message."
  2000)

(def ^{:stratum 0} ^:private truncated-output-suffix
  "Suffix appended when test-runner output is truncated for display."
  "\n...")

(def ^{:stratum 0} ^:private ran-tests-pattern
  "clojure.test's per-namespace count line. A Polylith run prints one per
   namespace; the run's totals are the SUM over every match, never the
   first match alone (series 8, 2026-09-04: the first namespace's
   `Ran 3 tests` stood in for a 911-namespace run)."
  #"Ran (\d+) tests? containing (\d+) assertions?")

(def ^{:stratum 0} ^:private namespace-summary-pattern
  "clojure.test's per-namespace `F failures, E errors.` line, anchored to
   the start of a line so the runner's per-project roll-up
   (`Test results: N passes, F failures, E errors.`), which repeats the
   same numbers, is not counted a second time."
  #"(?m)^(\d+) failures?,\s*(\d+) errors?")

(def ^{:stratum 0} ^:private failure-header-pattern
  "One clojure.test failure header: `FAIL in (test-name) (file:line)` or
   `ERROR in (test-name) (file:line)`. The location group is optional —
   a fixture that throws before any test runs prints none."
  #"^(FAIL|ERROR) in \(([^)]*)\)(?: \(([^)]*)\))?")

(def ^{:stratum 0} ^:private max-failure-blocks
  "Failure blocks kept per run. A compile error can fail every test in a
   namespace; twenty shows the pattern without flooding the implementer
   prompt."
  20)

(def ^{:stratum 0} ^:private max-failure-block-lines
  "Detail lines kept under one failure header — the `expected:`/`actual:`
   pair plus a short stack is enough to act on."
  12)

(def ^{:stratum 0} ^:private max-named-failures
  "Failing test names spelled out in the verify summary; any beyond this
   are counted, not listed."
  5)

;------------------------------------------------------------------------------ Layer 0.5
;; Test execution helpers
(defn- ^{:stratum 0} test-error-result
  "Build a test-results map representing an error (exception, unparseable output, etc.)."
  [message]
  {:passed? false :test-count 0 :assertion-count 0
   :fail-count 0 :error-count 1 :output message})

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

;; Interceptor implementation
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

(def ^{:stratum 0} ^:private verdicts
  "Phase 3 verdict tag set for verify. Mirrors review's shape (Phase 2b)
   with verify-specific terminal kinds:

     :approved            — gates passed; `:phase/succeed` event
     :repair-requested    — generic test failure with `:on-fail` set;
                            FSM redirects to implement (subject to
                            budget)
     :verify/timeout      — test process timed out (hung BB/cargo); FSM
                            terminates because retrying implement won't
                            unstick the process
     :verify/rate-limited — provider quota blocked the phase; FSM
                            terminates because retrying implement won't
                            change LLM quota
     :exhausted           — failed but no redirect target configured"
  #{:approved :repair-requested :verify/timeout :verify/rate-limited :exhausted})

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

(defn- ^{:stratum 0} sum-groups
  "Sum capture group `group` over every match of `pattern` in `output`."
  [pattern group output]
  (reduce + 0 (map #(parse-long (nth % group)) (re-seq pattern output))))

(defn- ^{:stratum 0} synthesized-error-count
  "The parsed error count, or one phantom error when every parsed count is
   clean yet the runner exited non-zero (a brick that failed to compile
   leaves no summary line). Counted failures are never topped up."
  [fail-count error-count exit-code]
  (if (and (zero? fail-count) (zero? error-count) (not (zero? exit-code)))
    1
    error-count))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} failure-detail-line?
  "A line that still belongs to the failure block above it: non-blank and
   not the next failure header."
  [line]
  (and (not (str/blank? line))
       (nil? (re-find failure-header-pattern line))))

(defn- ^{:stratum 1} failure-header-index
  "Index of `line` when it is a failure header, else nil (for keep-indexed)."
  [index line]
  (when (re-find failure-header-pattern line)
    index))

(defn- ^{:stratum 1} failing-test-list
  "Comma-separated failing test names, at most `max-named-failures`, with
   the remainder counted."
  [names]
  (let [shown (take max-named-failures names)
        extra (- (count names) (count shown))]
    (cond-> (str/join ", " shown)
      (pos? extra) (str " (+" extra " more)"))))

(defn- ^{:stratum 1} bounded-output-preview
  "Return a bounded, trimmed preview of test runner output."
  [output]
  (let [trimmed (str/trim (str output))]
    (cond-> (subs trimmed 0 (min (count trimmed) verify-error-preview-limit))
      (> (count trimmed) verify-error-preview-limit)
      (str truncated-output-suffix))))

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
        gate-failed? (= :failed (:phase/status (get-in ctx [:phase])))
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

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} failure-block
  "The failure header at the head of `lines` plus its detail lines, bounded
   by `max-failure-block-lines`."
  [lines]
  (let [[_ kind test-name location] (re-find failure-header-pattern (first lines))
        detail (->> (rest lines)
                    (take-while failure-detail-line?)
                    (take max-failure-block-lines))]
    {:kind     (if (= "FAIL" kind) :fail :error)
     :test     test-name
     :location location
     :detail   (str/join "\n" detail)}))

(defn- ^{:stratum 2} verify-failure-message
  "Build the user-facing verify failure message from parsed test results."
  [test-results raw-fails raw-errors]
  (cond
    ;; Timeout: surface the formatted timeout output directly so the substring
    ;; matched by `timeout-message-fragment` lands in the phase result, which
    ;; routes leave-verify to the non-actionable path (no redirect-to-implement).
    (:timed-out? test-results)
    (str (:output test-results))

    (:parse-error? test-results)
    (str (messages/t :verify/output-unparseable)
         (when-let [preview (not-empty (bounded-output-preview (:output test-results)))]
           (str "\n" preview)))

    :else
    (let [names (map :test (:failures test-results))
          counts (messages/t :verify/tests-failed {:fail-count raw-fails :error-count raw-errors})]
      (cond-> counts
        (seq names) (str " " (messages/t :verify/failing-tests
                                         {:tests (failing-test-list names)}))))))

;------------------------------------------------------------------------------ Layer 3

(defn- ^{:stratum 3} failure-blocks
  "Every `FAIL in` / `ERROR in` block in clojure.test output, in order,
   capped at `max-failure-blocks`. Names the failing tests so a consumer
   three namespaces into a 6,000-line run is not hidden behind the first
   namespace's clean summary."
  [output]
  (let [lines (vec (str/split-lines (str output)))]
    (->> (keep-indexed failure-header-index lines)
         (take max-failure-blocks)
         (mapv #(failure-block (subvec lines %))))))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} parse-test-output
  "Parse test summary output. Handles Clojure (bb test) and Rust (cargo test) formats.

   Clojure: 'Ran N tests containing M assertions. F failures, E errors.'
   Rust:    'test result: ok. N passed; M failed; ...' (exit code is authoritative)"
  [output exit-code]
  (cond
    (re-find #"(?i)No changed bricks|nothing to test" output)
    ;; "Nothing to test" is only a pass when the runner ALSO exited cleanly. A
    ;; non-zero exit alongside this text means the runner failed to discover or
    ;; compile the test namespaces (e.g. a load/compile error before any test
    ;; ran) — that must not be reported as success.
    ;; A non-zero exit is reflected as one error so the downstream failure
    ;; message/metrics don't read "0 failures, 0 errors" on a failed run.
    {:passed? (zero? exit-code) :test-count 0 :assertion-count 0 :fail-count 0
     :error-count (if (zero? exit-code) 0 1)
     :no-tests? true :output output}

    ;; Rust / cargo test
    (re-find #"test result:" output)
    (let [result-match (re-find #"test result: (\w+)\. (\d+) passed; (\d+) failed" output)
          passed (if result-match (parse-long (nth result-match 2)) 0)
          failed (if result-match (parse-long (nth result-match 3)) 0)]
      {:passed? (zero? exit-code)
       :test-count (+ passed failed)
       :assertion-count (+ passed failed)
       :fail-count failed
       :error-count 0
       :output output})

    ;; Clojure / bb test
    :else
    (let [ran-any?     (some? (re-find ran-tests-pattern output))
          summary-any? (some? (re-find namespace-summary-pattern output))]
      (if (and ran-any? summary-any?)
        (let [fail-count  (sum-groups namespace-summary-pattern 1 output)
              error-count (sum-groups namespace-summary-pattern 2 output)]
          ;; Exit code is authoritative alongside the parsed counts: a brick
          ;; whose tests fail to COMPILE (or a runner that exits non-zero for
          ;; any reason) can still leave a "Ran N tests ... 0 failures, 0
          ;; errors" line from OTHER bricks in the output. Requiring a zero
          ;; exit too means such a run fails verify instead of being reported
          ;; as passing on the text alone.
          {:passed? (and (zero? fail-count) (zero? error-count) (zero? exit-code))
           :test-count (sum-groups ran-tests-pattern 1 output)
           :assertion-count (sum-groups ran-tests-pattern 2 output)
           :fail-count fail-count
           :error-count (synthesized-error-count fail-count error-count exit-code)
           :failures (failure-blocks output)
           :output output})
        (assoc (test-error-result output) :parse-error? true)))))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} run-tests!
  "Run tests in the worktree. Infers the test command from the repo structure
   unless test-cmd is supplied explicitly.

   Bounded by :timeout-ms (default `default-test-timeout-ms`). On timeout
   the test process and its children are destroyed forcibly and a failed
   result is returned whose :output contains `timeout-message-fragment` so
   leave-verify routes it as a non-actionable verify failure rather than
   redirecting back to implement.

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
            done-fut (future @proc)
            done?    (not= ::timeout (deref done-fut timeout-ms ::timeout))]
        (if done?
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
                   :timed-out? true))))
      (catch Exception e
        (test-error-result (.getMessage e))))))

(defn ^{:stratum 5} run-tests-in-capsule!
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

;------------------------------------------------------------------------------ Layer 6

(defn ^{:stratum 6} enter-verify
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
                     (verify-failure-message test-results raw-fails raw-errors))
        result     (if passed?
                     (phase/success env-id summary metrics)
                     (phase/error   env-id summary summary metrics))]

    (phase/enter-context ctx :verify nil gates budget start-time result)))

;------------------------------------------------------------------------------ Layer 7

;; Registry method
(defmethod ^{:stratum 7} phase/get-phase-interceptor-method :verify
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::verify
     :config merged
     :enter (fn [ctx]
              (enter-verify (assoc ctx :phase-config merged)))
     :leave leave-verify
     :error error-verify}))

;; Register defaults on load
(phase/register-phase-defaults! :verify default-config)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (phase/get-phase-interceptor {:phase :verify})
  (phase/get-phase-interceptor {:phase :verify :on-fail :implement})
  (phase/phase-defaults :verify)
  :leave-this-here)
