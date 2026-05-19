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

(ns ai.miniforge.phase-software-factory.verify-failure-modes-test
  "Tests for verify phase failure modes.

   Covers: environment-based test execution, fail-fast on missing env-id,
   test runner errors, and leave-verify redirect suppression."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.string :as str]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.phase-software-factory.messages :as messages]
   [ai.miniforge.phase-software-factory.verify :as verify]))

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

;------------------------------------------------------------------------------ Test Fixtures

(defn create-base-context
  "Base context with executor environment."
  []
  {:execution/id (random-uuid)
   :execution/environment-id (random-uuid)
   :execution/worktree-path "/tmp/test-worktree"
   :execution/input {:description "Test task" :title "Test" :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {}})

(defn with-passing-tests [body-fn]
  (let [run-var (resolve 'ai.miniforge.phase-software-factory.verify/run-tests!)]
    (with-redefs-fn
      {run-var (fn [_ & _opts] {:passed? true :test-count 5 :assertion-count 10
                                :fail-count 0 :error-count 0
                                :output "Ran 5 tests containing 10 assertions.\n0 failures, 0 errors."})}
      body-fn)))

(defn with-failing-tests [body-fn]
  (let [run-var (resolve 'ai.miniforge.phase-software-factory.verify/run-tests!)]
    (with-redefs-fn
      {run-var (fn [_ & _opts] {:passed? false :test-count 3 :assertion-count 6
                                :fail-count 2 :error-count 1
                                :output "Ran 3 tests.\n2 failures, 1 error."})}
      body-fn)))

;------------------------------------------------------------------------------ Enter Tests

(deftest verify-succeeds-when-tests-pass-test
  (testing "verify phase returns :success when test suite passes"
    (with-passing-tests
      (fn []
        (let [ctx (-> (create-base-context)
                      (assoc :phase-config {:phase :verify}))
              interceptor (phase/get-phase-interceptor {:phase :verify})
              result ((:enter interceptor) ctx)]

          (is (= :success (get-in result [:phase :result :status]))
              "Verify should succeed when all tests pass")

          (is (= 0 (get-in result [:phase :result :metrics :fail-count]))
              "No failures captured in metrics when all tests pass")
          (is (pos? (get-in result [:phase :result :metrics :pass-count]))
              "Pass count captured in metrics"))))))

(deftest verify-fails-when-tests-fail-test
  (testing "verify phase returns :error when test suite fails"
    (with-failing-tests
      (fn []
        (let [ctx (-> (create-base-context)
                      (assoc :phase-config {:phase :verify}))
              interceptor (phase/get-phase-interceptor {:phase :verify})
              result ((:enter interceptor) ctx)]

          (is (= :error (get-in result [:phase :result :status]))
              "Verify should fail when tests fail")

          (is (some? (get-in result [:phase :result :error :message]))
              "Error message should be present")

          (is (pos? (get-in result [:phase :result :metrics :fail-count]))
              "Fail count captured in metrics when tests fail"))))))

(deftest verify-with-missing-environment-id-test
  (testing "verify phase fails fast when no execution environment-id is in context"
    (let [ctx (-> (create-base-context)
                  (dissoc :execution/environment-id)
                  (assoc :phase-config {:phase :verify}))
          interceptor (phase/get-phase-interceptor {:phase :verify})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Verify phase has no execution environment"
                            ((:enter interceptor) ctx))
          "Verify should throw when no execution environment is available"))))

(deftest verify-handles-test-runner-error-gracefully-test
  (testing "verify phase handles test runner exception as test failure"
    (let [run-var (resolve 'ai.miniforge.phase-software-factory.verify/run-tests!)]
      (with-redefs-fn
        {run-var (fn [_ & _opts] {:passed? false :test-count 0 :fail-count 0 :error-count 1
                                  :output "bb: command not found"})}
        (fn []
          (let [ctx (-> (create-base-context)
                        (assoc :phase-config {:phase :verify}))
                interceptor (phase/get-phase-interceptor {:phase :verify})
                result ((:enter interceptor) ctx)]
            (is (= :error (get-in result [:phase :result :status]))
                "Runner error should produce :error result")
            (is (some? (get-in result [:phase :result :metrics :test-output]))
                "Test output captured in metrics even on runner error")))))))

(deftest verify-preserves-unparseable-test-output-test
  (testing "unparseable test output is surfaced as actionable verify feedback"
    (let [run-var (resolve 'ai.miniforge.phase-software-factory.verify/run-tests!)]
      (with-redefs-fn
        {run-var (fn [_ & _opts] {:passed? false
                                  :test-count 0
                                  :assertion-count 0
                                  :fail-count 0
                                  :error-count 1
                                  :parse-error? true
                                  :output "Syntax error compiling at src/example.clj:12:3"})}
        (fn []
          (let [ctx (-> (create-base-context)
                        (assoc :phase-config {:phase :verify}))
                interceptor (phase/get-phase-interceptor {:phase :verify})
                result ((:enter interceptor) ctx)
                message (get-in result [:phase :result :error :message])]
            (is (str/includes? message (messages/t :verify/output-unparseable)))
            (is (str/includes? message "Syntax error compiling"))))))))

(deftest parse-test-output-marks-unparseable-output-test
  (testing "the real parser treats unparseable output as one actionable error"
    (let [result (verify/parse-test-output "Syntax error compiling at src/example.clj:12:3"
                                           1)]
      (is (false? (:passed? result)))
      (is (true? (:parse-error? result)))
      (is (= 1 (:error-count result)))
      (is (= 0 (:fail-count result))))))

(deftest verify-bounds-unparseable-output-preview-test
  (testing "long unparseable output is bounded in the verify error message"
    (let [run-var (resolve 'ai.miniforge.phase-software-factory.verify/run-tests!)
          preview-limit @#'verify/verify-error-preview-limit
          suffix @#'verify/truncated-output-suffix
          output (apply str (repeat (+ preview-limit 50) "x"))]
      (with-redefs-fn
        {run-var (fn [_ & _opts] {:passed? false
                                  :test-count 0
                                  :assertion-count 0
                                  :fail-count 0
                                  :error-count 1
                                  :parse-error? true
                                  :output output})}
        (fn []
          (let [ctx (-> (create-base-context)
                        (assoc :phase-config {:phase :verify}))
                interceptor (phase/get-phase-interceptor {:phase :verify})
                result ((:enter interceptor) ctx)
                message (get-in result [:phase :result :error :message])
                prefix (messages/t :verify/output-unparseable)]
            (is (str/ends-with? message suffix))
            (is (= (+ (count prefix) 1 preview-limit (count suffix))
                   (count message)))))))))

;------------------------------------------------------------------------------ Leave-verify redirect suppression tests (PR #288)

(defn make-leave-ctx
  "Build a minimal context suitable for leave-verify."
  [result on-fail]
  (cond-> {:phase {:started-at (- (System/currentTimeMillis) 1000)
                   :result result
                   :iterations 1}
           :execution {:phases-completed []}
           :execution/metrics {:tokens 0 :duration-ms 0}}
    on-fail (assoc :phase-config {:on-fail on-fail})))

(deftest leave-verify-redirects-on-normal-failure-test
  (testing "normal verify failure with :on-fail configured requests a redirect"
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "Tests failed: 3 assertions"}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (= :implement (phase/transition-target (get result :phase))))
      (is (phase/failed? (get result :phase))))))

(deftest leave-verify-redirects-parse-error-output-with-provider-words-test
  (testing "parse-error previews are actionable even when test output contains provider-like fragments"
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message (str (messages/t :verify/output-unparseable)
                                                     "\nHTTP 429 from project test output timed out")}
                               :metrics {:parse-error? true}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (= :implement (phase/transition-target (get result :phase))))
      (is (phase/failed? (get result :phase))))))

(deftest leave-verify-redirects-on-timeout-with-flag-test
  (testing "timeout error redirects to :implement and carries :phase/timeout? so the implementer prompt can frame it as a hang"
    ;; Inverts the prior policy. Old code skipped redirect on timeout
    ;; because `retrying implement won't fix a stalled test process` —
    ;; but the actual fix IS in implement: identify and repair the test
    ;; that hangs. The :phase/timeout? flag rides into :task/verify-failures
    ;; so the implementer's verify-section header switches to the loud
    ;; `TEST RUNNER HUNG` framing.
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "Verify test runner timed out after 600000ms (cmd: bb test)"}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (= :implement (phase/transition-target (get result :phase)))
          "timeout MUST now redirect to the on-fail target (was skipped pre-PR #915 follow-up)")
      (is (phase/failed? (get result :phase)))
      (is (true? (get-in result [:phase :error :timeout?]))
          ":error :timeout? must still be set so downstream consumers can branch")
      (is (true? (get-in result [:phase :phase/timeout?]))
          ":phase/timeout? must ride on the redirected phase so build-verify-failures can pick it up"))))

(deftest leave-verify-no-redirect-on-rate-limit-test
  (testing "rate-limit error does NOT redirect even with :on-fail configured"
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "429 rate limit exceeded"}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (not (phase/redirect-requested? (get result :phase))))
      (is (phase/failed? (get result :phase)))
      (is (some? (get-in result [:phase :error :rate-limited?]))))

    (testing "rate-limit variant: you've hit your limit"
      (let [ctx (make-leave-ctx {:status :error
                                 :error {:message "You've hit your limit · resets 7pm"}}
                                :implement)
            result (verify/leave-verify ctx)]
        (is (not (phase/redirect-requested? (get result :phase))))))))

(deftest leave-verify-no-redirect-without-on-fail-test
  (testing "normal failure without :on-fail does not request a redirect"
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "Tests failed"}}
                              nil)
          result (verify/leave-verify ctx)]
      (is (not (phase/redirect-requested? (get result :phase))))
      (is (phase/failed? (get result :phase))))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.verify-failure-modes-test)
  :leave-this-here)
