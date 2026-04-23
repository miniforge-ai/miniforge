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
  (:require            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-software-factory.phase-config :as phase-config]
            
            [ai.miniforge.phase-software-factory.messages :as messages]
            [babashka.process :as process]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :verify))

(def ^:private timeout-message-fragment
  "Substring that marks a non-actionable verify timeout."
  "timed out")

(def ^:private verify-rate-limit-pattern
  "Pattern for non-actionable verify failures caused by provider throttling."
  #"(?i)rate.?limit|429|you've hit your limit|quota.?exceeded")

;; Register defaults on load
(phase/register-phase-defaults! :verify default-config)

;------------------------------------------------------------------------------ Layer 0.5
;; Test execution helpers

(defn- test-error-result
  "Build a test-results map representing an error (exception, unparseable output, etc.)."
  [message]
  {:passed? false :test-count 0 :assertion-count 0
   :fail-count 0 :error-count 1 :output message})

(defn parse-test-output
  "Parse test summary output. Handles Clojure (bb test) and Rust (cargo test) formats.

   Clojure: 'Ran N tests containing M assertions. F failures, E errors.'
   Rust:    'test result: ok. N passed; M failed; ...' (exit code is authoritative)"
  [output exit-code]
  (cond
    (re-find #"(?i)No changed bricks|nothing to test" output)
    {:passed? true :test-count 0 :assertion-count 0 :fail-count 0 :error-count 0
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
    (let [ran-match  (re-find #"Ran (\d+) tests? containing (\d+) assertions?" output)
          fail-match (re-find #"(\d+) failures?,\s*(\d+) errors?" output)]
      (if (and ran-match fail-match)
        (let [test-count  (parse-long (nth ran-match 1))
              fail-count  (parse-long (nth fail-match 1))
              error-count (parse-long (nth fail-match 2))]
          {:passed? (and (zero? fail-count) (zero? error-count))
           :test-count test-count
           :assertion-count (parse-long (nth ran-match 2))
           :fail-count fail-count
           :error-count error-count
           :output output})
        (assoc (test-error-result output) :error-count 0 :parse-error? true)))))

(defn infer-test-command
  "Infer the test command from the repo structure.
   Checks for bb.edn (Clojure), Cargo.toml (Rust), package.json (JS) in that order.
   Falls back to 'bb test'."
  [worktree-path]
  (cond
    (fs/exists? (fs/path worktree-path "Cargo.toml")) "cargo test --workspace"
    (fs/exists? (fs/path worktree-path "package.json")) "npm test"
    :else "bb test"))

(defn run-tests!
  "Run tests in the worktree. Infers the test command from the repo structure
   unless test-cmd is supplied explicitly.
   Returns parsed test results map."
  [worktree-path & {:keys [test-cmd]}]
  (let [cmd (or test-cmd (infer-test-command worktree-path))]
    (try
      (let [result (process/shell
                     {:dir (str worktree-path)
                      :out :string :err :string :continue true}
                     "sh" "-c" cmd)]
        (parse-test-output (str (:out result "") "\n" (:err result ""))
                           (:exit result 1)))
      (catch Exception e
        (test-error-result (.getMessage e))))))

(defn run-tests-in-capsule!
  "Run tests inside a task capsule via execute-fn (N11 §6).
   Routes the test command through the executor instead of host process/shell.
   execute-fn is dag-exec/execute! passed through context to avoid cross-component requires."
  [execute-fn executor env-id worktree-path & {:keys [test-cmd]}]
  (let [cmd (or test-cmd "bb test")]
    (try
      (let [result (execute-fn executor env-id cmd {:workdir worktree-path})
            data   (:data result)]
        (parse-test-output (str (get data :stdout "") "\n" (get data :stderr ""))
                           (get data :exit-code 1)))
      (catch Exception e
        (test-error-result (.getMessage e))))))

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn enter-verify
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
        _ (when-not (get ctx :execution/environment-id)
            (throw (ex-info (messages/t :verify/no-environment)
                            {:phase :verify
                             :hint (messages/t :verify/no-environment-hint)})))

        worktree-path (or (get ctx :execution/worktree-path)
                          (get ctx :worktree-path)
                          (System/getProperty "user.dir"))

        ;; Allow spec to override the test command; otherwise infer from repo structure
        test-cmd (or (get-in ctx [:execution/input :spec/test-command])
                     (get-in ctx [:execution/input :test-command]))

        ;; Run the test suite — inside capsule for governed mode, on host otherwise
        execute-fn (get ctx :execution/execute-fn)
        test-results (if (and (= :governed (get ctx :execution/mode))
                              execute-fn
                              (get ctx :execution/executor))
                       (run-tests-in-capsule! execute-fn
                                              (get ctx :execution/executor)
                                              (get ctx :execution/environment-id)
                                              worktree-path
                                              :test-cmd test-cmd)
                       (run-tests! worktree-path :test-cmd test-cmd))

        ;; Phase result carries environment reference and test metrics (N6 environment model).
        ;; No serialized code — changes live in the environment's worktree.
        ;; :metrics carries pass/fail counts and test-output for the evidence bundle.
        env-id     (get ctx :execution/environment-id)
        passed?    (:passed? test-results)
        pass-count (get test-results :test-count 0)
        raw-fails  (get test-results :fail-count 0)
        raw-errors (get test-results :error-count 0)
        metrics    (phase/test-metrics pass-count (+ raw-fails raw-errors) (get test-results :output ""))
        summary    (if passed?
                     (messages/t :verify/tests-passed {:pass-count pass-count})
                     (messages/t :verify/tests-failed {:fail-count raw-fails :error-count raw-errors}))
        result     (if passed?
                     (phase/success env-id summary metrics)
                     (phase/error   env-id summary summary metrics))]

    (phase/enter-context ctx :verify nil gates budget start-time result)))

(defn leave-verify
  "Post-processing for verification phase.

   Records test metrics: count, pass rate, coverage.
   When verification fails and :on-fail is configured, requests a redirect
   so the execution engine jumps to the target phase (typically :implement)."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        agent-status (:status result)
        gate-failed? (= :failed (:phase/status (get-in ctx [:phase])))
        timeout? (and (= :error agent-status)
                      (some-> (get-in result [:error :message])
                              (str/includes? timeout-message-fragment)))
        rate-limited? (and (= :error agent-status)
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
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] phase-status)
                        (assoc-in [:phase :metrics] metrics)
                        (assoc-in [:metrics :verification :duration-ms] duration-ms)
                        (assoc-in [:metrics :verification :repair-cycles] (dec iterations))
                        (update-in [:execution :phases-completed] (fnil conj []) :verify)
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))
        error-message (or (not-empty (get-in result [:error :message]))
                          (when gate-failed? (messages/t :verify/gate-failed))
                          (messages/t :verify/failed))]
    ;; When verify failed and on-fail is configured, redirect to target phase.
    ;; Timeout and rate-limit failures are not code quality issues — retrying
    ;; implement won't help, so we do not redirect in those cases.
    (doto (if (and (= :failed phase-status) on-fail
                     (not timeout?) (not rate-limited?))
              (let [phase-result (-> (:phase updated-ctx)
                                     (assoc :error
                                            {:message      error-message
                                             :agent-status agent-status
                                             :gate-failed? gate-failed?})
                                     (phase/request-redirect on-fail))]
                (assoc updated-ctx :phase phase-result))
              (cond-> updated-ctx
                (= :failed phase-status)
                (assoc-in [:phase :error]
                          {:message       error-message
                           :agent-status  agent-status
                           :timeout?      timeout?
                           :rate-limited? rate-limited?
                           :gate-failed?  gate-failed?})))
        (phase/emit-phase-completed! :verify
          {:outcome     (if (= :completed phase-status) :success :failure)
           :duration-ms duration-ms
           :tokens      (get metrics :tokens 0)}))))

(defn error-verify
  "Handle verification phase errors.

   On test failure, can redirect to :implement if on-fail specified."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 3)
        on-fail (get-in ctx [:phase-config :on-fail])]
    (cond
      ;; Within budget - retry
      (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))

      ;; Has on-fail transition - redirect
      on-fail
      (let [phase-result (-> (:phase ctx)
                             (assoc :status :failed)
                             (assoc :error (phase/exception-error ex))
                             (phase/request-redirect on-fail))]
        (assoc ctx :phase phase-result))

      ;; No recovery - propagate
      :else
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :error] (phase/exception-error ex))))))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod phase/get-phase-interceptor-method :verify
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::verify
     :config merged
     :enter (fn [ctx]
              (enter-verify (assoc ctx :phase-config merged)))
     :leave leave-verify
     :error error-verify}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (phase/get-phase-interceptor {:phase :verify})
  (phase/get-phase-interceptor {:phase :verify :on-fail :implement})
  (phase/phase-defaults :verify)
  :leave-this-here)
