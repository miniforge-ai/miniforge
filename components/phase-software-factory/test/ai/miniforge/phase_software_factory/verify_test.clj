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

(ns ai.miniforge.phase-software-factory.verify-test
  "Tests for the verify phase interceptor.

   Verify runs the test suite directly in the executor environment —
   no tester agent. Tests here cover: environment-based test execution,
   fail-fast on missing environment-id, and pass/fail result shapes."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase-software-factory.verify :as verify]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]))

;------------------------------------------------------------------------------ Test fixtures

(def ^:private run-tests-var
  #'verify/run-tests!)

(def phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (try
      (binding [loader/phase-loader-config-resource phase-test-config-resource]
        (f))
      (finally
        (phase/reset-phase-loader!)))))

(defn create-base-context
  "Create base context with executor environment for testing."
  []
  {:execution/id (random-uuid)
   :execution/environment-id (random-uuid)
   :execution/worktree-path "/tmp/test-worktree"
   :execution/input {:description "Test task"
                     :title "Test"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}})

(defn with-mocked-test-runner
  "Run body-fn with run-tests! mocked to return a passing result."
  [body-fn]
  (with-redefs-fn
    {run-tests-var (fn [_ & _opts] {:passed? true :test-count 5 :assertion-count 10
                                    :fail-count 0 :error-count 0 :output "Ran 5 tests containing 10 assertions.\n0 failures, 0 errors."})}
    body-fn))

(defn with-failing-test-runner
  "Run body-fn with run-tests! mocked to return a failing result."
  [body-fn]
  (with-redefs-fn
    {run-tests-var (fn [_ & _opts] {:passed? false :test-count 3 :assertion-count 6
                                    :fail-count 2 :error-count 0
                                    :output "Ran 3 tests containing 6 assertions.\n2 failures, 0 errors."})}
    body-fn))

;------------------------------------------------------------------------------ Layer 0: Defaults tests

(deftest default-config-test
  (testing "default config has correct structure"
    (is (nil? (:agent verify/default-config))
        "Verify phase has no agent — runs tests directly")
    (is (= [:pre-verify-lint :tests-pass :coverage :policy-verify] (:gates verify/default-config)))
    (is (map? (:budget verify/default-config)))
    (is (= 3 (get-in verify/default-config [:budget :iterations])))))

(deftest phase-defaults-registration-test
  (testing "verify phase defaults are registered"
    (let [defaults (phase/phase-defaults :verify)]
      (is (some? defaults))
      (is (nil? (:agent defaults))
          "Verify has no agent in the new environment model")
      (is (= [:pre-verify-lint :tests-pass :coverage :policy-verify] (:gates defaults))))))

;------------------------------------------------------------------------------ Layer 1: Interceptor enter tests

(deftest enter-verify-basic-test
  (testing "enter-verify sets up phase context and runs tests"
    (with-mocked-test-runner
      (fn []
        (let [ctx (assoc (create-base-context) :phase-config {:phase :verify})
              result (verify/enter-verify ctx)]

          (testing "phase metadata is set"
            (is (= :verify (get-in result [:phase :name])))
            (is (nil? (get-in result [:phase :agent]))
                "No agent in new environment model")
            (is (= [:pre-verify-lint :tests-pass :coverage :policy-verify] (get-in result [:phase :gates])))
            (is (= :running (get-in result [:phase :status])))
            (is (number? (get-in result [:phase :started-at]))))

          (testing "budget is set from defaults"
            (is (= 3 (get-in result [:phase :budget :iterations]))))

          (testing "result carries test metrics in new environment model shape"
            (is (= :success (get-in result [:phase :result :status])))
            (is (some? (get-in result [:phase :result :environment-id]))
                "Result references the execution environment-id")
            (is (string? (get-in result [:phase :result :summary]))
                "Result carries a human-readable summary")
            (is (pos? (get-in result [:phase :result :metrics :pass-count]))
                "Pass count captured in metrics")
            (is (= 0 (get-in result [:phase :result :metrics :fail-count]))
                "Zero failures captured in metrics")
            (is (string? (get-in result [:phase :result :metrics :test-output]))
                "Test output string captured in metrics for evidence bundle")))))))

(deftest enter-verify-failing-tests-test
  (testing "enter-verify sets :error status when tests fail"
    (with-failing-test-runner
      (fn []
        (let [ctx (assoc (create-base-context) :phase-config {:phase :verify})
              result (verify/enter-verify ctx)]
          (is (= :error (get-in result [:phase :result :status])))
          (is (some? (get-in result [:phase :result :error :message])))
          ;; Fail count captured in metrics for implement-retry loop
          (is (pos? (get-in result [:phase :result :metrics :fail-count]))
              "Fail count captured in metrics when tests fail"))))))

(deftest enter-verify-fails-fast-without-environment-test
  (testing "enter-verify throws when :execution/environment-id is absent"
    (let [ctx (-> (create-base-context)
                  (dissoc :execution/environment-id)
                  (assoc :phase-config {:phase :verify}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Verify phase has no execution environment"
                            (verify/enter-verify ctx))))))

(deftest require-environment-result-returns-anomaly-test
  (testing "missing verify environment is available as anomaly data"
    (let [ctx (-> (create-base-context)
                  (dissoc :execution/environment-id))
          result (#'verify/require-environment-result ctx)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= :anomalies.phase/enter-failed (:anomaly/subtype result)))
      (is (= :verify (get-in result [:anomaly/data :phase]))))))

(deftest enter-verify-uses-execution-worktree-path-test
  (testing "enter-verify passes :execution/worktree-path to test runner"
    (let [captured-path (atom nil)]
      (with-redefs-fn
        {run-tests-var (fn [path & _opts]
                         (reset! captured-path path)
                         {:passed? true :test-count 1 :fail-count 0 :error-count 0})}
        (fn []
          (let [ctx (-> (create-base-context)
                        (assoc :execution/worktree-path "/tmp/my-worktree")
                        (assoc :phase-config {:phase :verify}))]
            (verify/enter-verify ctx)
            (is (= "/tmp/my-worktree" @captured-path))))))))

;------------------------------------------------------------------------------ Verify-stall coverage
;;
;; Regression coverage for the 2026-05-18 dogfood incident on workflow
;; aadac7ce. Verify entered, emitted :phase/milestone-started, then went
;; silent for 30+ minutes because `run-tests!` shelled out to `bb test`
;; without a deadline. The workflow had no events, no liveness signal, no
;; recovery — exactly the failure class the running spec was meant to
;; address for agent streams, surfacing in a sibling code path.
;;
;; These tests pin the timeout contract on run-tests! and the propagation
;; through enter-verify and verify-failure-message into the phase result
;; (so leave-verify's existing timeout-detection path becomes reachable
;; instead of being silently dead code).

(deftest run-tests-aborts-hung-process-within-timeout-test
  (testing "run-tests! must destroy a hung test process when :timeout-ms elapses"
    (let [t0      (System/nanoTime)
          ;; sleep 600 = a process that would block run-tests! indefinitely.
          ;; Before the fix this assertion timed out the entire test run;
          ;; after the fix the deadline destroys the child within ~1s.
          result  (verify/run-tests! "/tmp" :test-cmd "sleep 600" :timeout-ms 750)
          elapsed (quot (- (System/nanoTime) t0) 1000000)]
      (is (false? (:passed? result))
          "a destroyed test process must produce a failed result")
      (is (true? (:timed-out? result))
          ":timed-out? sentinel must be set so downstream consumers can branch on cause")
      (is (str/includes? (str (:output result)) "timed out")
          ":output must contain the substring `timed out` so leave-verify routes correctly")
      (is (< elapsed 5000)
          (str "must abort within seconds, not block the workflow indefinitely "
               "(elapsed: " elapsed "ms)")))))

(deftest run-tests-honours-default-timeout-when-not-supplied-test
  (testing "run-tests! falls back to default-test-timeout-ms when no :timeout-ms arg given"
    ;; Pin the default to a positive number — protects against a refactor
    ;; that nils the default and silently restores the unbounded behaviour
    ;; that caused the 2026-05-18 verify hang.
    (is (pos-int? verify/default-test-timeout-ms))
    (is (<= verify/default-test-timeout-ms (* 60 60 1000))
        "default must stay under 1 hour — otherwise a hang still freezes the workflow for too long")))

(deftest enter-verify-propagates-spec-test-timeout-test
  (testing "enter-verify threads :spec/test-timeout-ms from :execution/input into run-tests!"
    (let [captured-opts (atom nil)]
      (with-redefs-fn
        {run-tests-var (fn [_path & opts]
                         (reset! captured-opts (apply hash-map opts))
                         {:passed? true :test-count 1 :fail-count 0 :error-count 0})}
        (fn []
          (let [ctx (-> (create-base-context)
                        (assoc-in [:execution/input :spec/test-timeout-ms] 12345)
                        (assoc :phase-config {:phase :verify}))]
            (verify/enter-verify ctx)
            (is (= 12345 (:timeout-ms @captured-opts))
                (str ":spec/test-timeout-ms must flow into run-tests! — "
                     "the spec is the user-facing budget surface for the verify deadline"))))))))

(deftest enter-verify-uses-default-timeout-when-no-override-test
  (testing "enter-verify falls back to default-test-timeout-ms when neither spec nor config sets one"
    (let [captured-opts (atom nil)]
      (with-redefs-fn
        {run-tests-var (fn [_path & opts]
                         (reset! captured-opts (apply hash-map opts))
                         {:passed? true :test-count 1 :fail-count 0 :error-count 0})}
        (fn []
          (let [ctx (-> (create-base-context)
                        (assoc :phase-config {:phase :verify}))]
            (verify/enter-verify ctx)
            (is (= verify/default-test-timeout-ms (:timeout-ms @captured-opts))
                "default deadline must be applied even when spec is silent")))))))

(deftest run-tests-kills-child-process-not-just-shell-test
  (testing "destroy-process-tree! kills `sh -c <cmd>` AND its descendant test runner"
    ;; `sh -c "sleep 600"` forks sleep as a child; .destroyForcibly on the
    ;; sh parent does not propagate to sleep, so the production code used
    ;; to leak the actual test-runner process after a verify timeout.
    ;; This is what we observed as PID 31181 still alive 30+ min after
    ;; killing the parent. After the fix the descendant tree is walked
    ;; first, so no PID survives.
    (letfn [(descendant-sleeps []
              ;; Walk the JVM's current process descendants for a surviving
              ;; `sleep`. `.orElse` (not `.get`) — a just-killed zombie can
              ;; report an empty command Optional, which must read as "not
              ;; an orphan", not throw.
              (->> (.. (java.lang.ProcessHandle/current) descendants (toArray))
                   (filter (fn [^java.lang.ProcessHandle ph]
                             (-> ph .info .command (.orElse "")
                                 (str/includes? "sleep"))))))]
      (let [;; sleep 7200 in the shell so the test fails red if the tree
            ;; isn't actually killed (CI noticing a 2h orphan process).
            result (verify/run-tests! "/tmp" :test-cmd "sleep 7200" :timeout-ms 500)]
        (is (true? (:timed-out? result)))
        ;; Bounded wait, not a fixed beat: the kill lands immediately but a
        ;; loaded CI runner can take >250ms to clear the process table, which
        ;; a fixed sleep misread as a leak (flaked on main + PR CI,
        ;; 2026-07-18/19). The deadline only spends fully when the tree
        ;; genuinely leaks — the 2h sleep this test exists to catch.
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (loop []
            (when (and (seq (descendant-sleeps))
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 100)
              (recur))))
        (let [orphans (descendant-sleeps)]
          (is (empty? orphans)
              (str "destroy-process-tree! must reap every descendant sleep process; "
                   "found " (count orphans) " orphan(s)")))))))

(deftest run-tests-in-capsule-passes-timeout-to-execute-fn-test
  (testing "run-tests-in-capsule! threads :timeout-ms into execute-fn opts so capsule executors honour it"
    ;; Governed-mode parity: without this, a hung `bb test` inside a
    ;; capsule produces the same silent verify hang we saw on the host
    ;; path on 2026-05-18. The Copilot review on PR #915 flagged this
    ;; explicitly.
    (let [captured-opts (atom nil)
          execute-fn    (fn [_executor _env-id _cmd opts]
                          (reset! captured-opts opts)
                          {:data {:stdout "Ran 1 tests containing 1 assertions.\n0 failures, 0 errors."
                                  :stderr ""
                                  :exit-code 0}})]
      (verify/run-tests-in-capsule! execute-fn :exec :env "/tmp"
                                    :test-cmd "bb test"
                                    :timeout-ms 123456)
      (is (= 123456 (:timeout-ms @captured-opts))
          ":timeout-ms must reach the executor's execute! opts"))))

(deftest run-tests-in-capsule-surfaces-executor-timeout-as-timed-out-test
  (testing "an executor that returns :timed-out? gets routed as a timeout on the result"
    (let [execute-fn (fn [_ _ _ _]
                       {:data {:stdout "" :stderr "" :exit-code 124}
                        :timed-out? true})
          result     (verify/run-tests-in-capsule! execute-fn :exec :env "/tmp"
                                                   :test-cmd "bb test"
                                                   :timeout-ms 1000)]
      (is (true? (:timed-out? result))
          ":timed-out? from the executor must survive parsing")
      (is (str/includes? (str (:output result)) "timed out")
          ":output must carry the timeout fragment so leave-verify routes correctly"))))

(deftest enter-verify-timed-out-result-includes-fragment-test
  (testing "a :timed-out? test result becomes a phase result whose summary contains `timed out`"
    ;; This is the wiring leave-verify depends on: it scans result :error :message
    ;; for `timeout-message-fragment` to skip the redirect-to-implement path.
    ;; If the fragment doesn't survive into the phase result, the dead-code
    ;; detection in leave-verify stays dead and timeouts get treated as
    ;; ordinary test failures (and route back to implement — wasting tokens).
    (with-redefs-fn
      {run-tests-var (fn [_path & _opts]
                       {:passed? false :test-count 0 :assertion-count 0
                        :fail-count 0 :error-count 1
                        :timed-out? true
                        :output "Verify test runner timed out after 1000ms (cmd: bb test)"})}
      (fn []
        (let [ctx (-> (create-base-context)
                      (assoc :phase-config {:phase :verify}))
              ctx-after (verify/enter-verify ctx)
              result (get-in ctx-after [:phase :result])]
          (is (= :error (:status result)))
          (is (str/includes? (str (:summary result)) "timed out")
              ":summary must carry the `timed out` fragment")
          (is (str/includes? (str (get-in result [:error :message])) "timed out")
              ":error :message must carry the fragment — leave-verify branches on this"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.verify-test)
  :leave-this-here)
