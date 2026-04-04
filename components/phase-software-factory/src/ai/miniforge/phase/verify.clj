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

(ns ai.miniforge.phase.verify
  "Verification phase interceptor.

   Runs the test suite directly inside the executor environment.
   No tester agent: the implementer wrote both source and test files;
   this phase validates them by running the test suite.
   Default gates: [:tests-pass :coverage]"
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.phase.phase-config :as phase-config]
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
(registry/register-phase-defaults! :verify default-config)

;------------------------------------------------------------------------------ Layer 0.5
;; Test execution helpers

(defn write-test-files!
  "Write test files from tester agent output to disk.
   Returns vector of written file paths."
  [worktree-path test-files]
  (mapv (fn [{:keys [path content]}]
          (let [full-path (fs/path worktree-path path)]
            (fs/create-dirs (fs/parent full-path))
            (spit (str full-path) content)
            path))
        test-files))

(defn parse-test-output
  "Parse clojure.test summary output.
   Looks for: 'Ran N tests containing M assertions. F failures, E errors.'
   Returns map with test result counts.

   NOTE: Currently Clojure-specific. When supporting other languages (Rust, JS, etc.),
   this should dispatch on a :test/framework or :language key from the workflow config."
  [output]
  (if (re-find #"(?i)No changed bricks|nothing to test" output)
    ;; Test runner intentionally skipped — no bricks changed. Treat as pass.
    {:passed? true :test-count 0 :assertion-count 0 :fail-count 0 :error-count 0
     :no-tests? true :output output}
    (let [ran-match (re-find #"Ran (\d+) tests? containing (\d+) assertions?" output)
          fail-match (re-find #"(\d+) failures?,\s*(\d+) errors?" output)]
      (if (and ran-match fail-match)
        (let [test-count (parse-long (nth ran-match 1))
              assertion-count (parse-long (nth ran-match 2))
              fail-count (parse-long (nth fail-match 1))
              error-count (parse-long (nth fail-match 2))]
          {:passed? (and (zero? fail-count) (zero? error-count))
           :test-count test-count
           :assertion-count assertion-count
           :fail-count fail-count
           :error-count error-count
           :output output})
        ;; Could not parse — treat as failure to be safe
        {:passed? false
         :test-count 0
         :assertion-count 0
         :fail-count 0
         :error-count 0
         :parse-error? true
         :output output}))))

(defn run-tests!
  "Run tests in the worktree via bb test.
   Returns parsed test results map."
  [worktree-path]
  (try
    (let [result (process/shell
                   {:dir (str worktree-path)
                    :out :string :err :string :continue true}
                   "bb" "test")]
      (parse-test-output (str (:out result "") "\n" (:err result ""))))
    (catch Exception e
      {:passed? false
       :test-count 0 :assertion-count 0
       :fail-count 0 :error-count 1
       :output (.getMessage e)})))

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn enter-verify
  "Execute verification phase.

   Runs the test suite directly inside the executor environment.
   No tester agent — the implementer already wrote test files.
   Fails fast if no execution environment is present in context."
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Emit phase-started telemetry event
        _ (phase/emit-phase-started! ctx :verify)

        ;; Fail fast if no executor environment has been acquired
        _ (when-not (get ctx :execution/environment-id)
            (throw (ex-info "Verify phase received no code artifact"
                            {:phase :verify
                             :hint "Workflow runner must acquire an execution environment before verify phase"})))

        worktree-path (or (get ctx :execution/worktree-path)
                          (get ctx :worktree-path)
                          (System/getProperty "user.dir"))

        ;; Run the test suite directly in the executor environment
        test-results (run-tests! worktree-path)

        ;; Phase result carries environment reference and test metrics (N6 environment model).
        ;; No serialized code — changes live in the environment's worktree.
        ;; :metrics carries pass/fail counts and test-output for the evidence bundle.
        env-id     (get ctx :execution/environment-id)
        pass-count (get test-results :test-count 0)
        fail-count (+ (get test-results :fail-count 0) (get test-results :error-count 0))
        summary    (if (:passed? test-results)
                     (str "All " pass-count " test(s) passed")
                     (str "Tests failed: " (get test-results :fail-count 0) " failure(s), "
                          (get test-results :error-count 0) " error(s)"))
        result     (if (:passed? test-results)
                     {:status         :success
                      :environment-id env-id
                      :summary        summary
                      :metrics        {:tokens 0 :duration-ms 0
                                       :pass-count  pass-count
                                       :fail-count  fail-count
                                       :test-output (get test-results :output "")}}
                     {:status         :error
                      :environment-id env-id
                      :summary        summary
                      :error          {:message (str "Tests failed: "
                                                     (get test-results :fail-count 0) " failures, "
                                                     (get test-results :error-count 0) " errors")}
                      :metrics        {:tokens 0 :duration-ms 0
                                       :pass-count  pass-count
                                       :fail-count  fail-count
                                       :test-output (get test-results :output "")}})]

    (-> ctx
        (assoc-in [:phase :name] :verify)
        (assoc-in [:phase :agent] nil)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running)
        (assoc-in [:phase :result] result))))

(defn leave-verify
  "Post-processing for verification phase.

   Records test metrics: count, pass rate, coverage.
   When verification fails and :on-fail is configured, sets :redirect-to
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
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; When verify failed and on-fail is configured, redirect to target phase
    ;; UNLESS the failure was a timeout or rate limit — those aren't code quality
    ;; issues and retrying implement won't help.
    (let [final-ctx (if (and (= :failed phase-status) on-fail
                             (not timeout?) (not rate-limited?))
                      (-> updated-ctx
                          (assoc-in [:phase :redirect-to] on-fail)
                          (assoc-in [:phase :error]
                                    {:message (or (not-empty (get-in result [:error :message]))
                                                  (when gate-failed? "Gate validation failed")
                                                  "Verification failed")
                                     :agent-status agent-status
                                     :gate-failed? gate-failed?}))
                      (cond-> updated-ctx
                        (= :failed phase-status)
                        (assoc-in [:phase :error]
                                  {:message (or (not-empty (get-in result [:error :message]))
                                                (when gate-failed? "Gate validation failed")
                                                "Verification failed")
                                   :agent-status agent-status
                                   :timeout? timeout?
                                   :rate-limited? rate-limited?
                                   :gate-failed? gate-failed?})))]
      ;; Emit phase-completed telemetry event
      (phase/emit-phase-completed! final-ctx :verify
        {:outcome (if (= :completed phase-status) :success :failure)
         :duration-ms duration-ms
         :tokens (:tokens metrics 0)})
      final-ctx)))

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
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :redirect-to] on-fail)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)}))

      ;; No recovery - propagate
      :else
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)})))))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod registry/get-phase-interceptor :verify
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::verify
     :config merged
     :enter (fn [ctx]
              (enter-verify (assoc ctx :phase-config merged)))
     :leave leave-verify
     :error error-verify}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (registry/get-phase-interceptor {:phase :verify})
  (registry/get-phase-interceptor {:phase :verify :on-fail :implement})
  (registry/phase-defaults :verify)
  :leave-this-here)
