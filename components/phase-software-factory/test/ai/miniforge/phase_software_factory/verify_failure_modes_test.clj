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

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

;------------------------------------------------------------------------------ Test Fixtures
(def ^{:stratum 0} ^:private run-tests-var
  #'verify/run-tests!)

(defn ^{:stratum 0} create-base-context
  "Base context with executor environment."
  []
  {:execution/id (random-uuid)
   :execution/environment-id (random-uuid)
   :execution/worktree-path "/tmp/test-worktree"
   :execution/input {:description "Test task" :title "Test" :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {}})

(deftest ^{:stratum 0} parse-test-output-marks-unparseable-output-test
  (testing "the real parser treats unparseable output as one actionable error"
    (let [result (verify/parse-test-output "Syntax error compiling at src/example.clj:12:3"
                                           1)]
      (is (false? (:passed? result)))
      (is (true? (:parse-error? result)))
      (is (= 1 (:error-count result)))
      (is (= 0 (:fail-count result))))))

(deftest ^{:stratum 0} parse-test-output-exit-code-is-authoritative-test
  ;; A change-scoped runner can print a clean "Ran N ... 0 failures, 0 errors"
  ;; line from in-scope bricks while another brick fails to COMPILE and the
  ;; runner exits non-zero. The parser must not report success on the text
  ;; alone — exit code is authoritative.
  (let [clean-text "Ran 5 tests containing 10 assertions.\n0 failures, 0 errors."]
    (testing "clean counts + zero exit -> pass"
      (is (true? (:passed? (verify/parse-test-output clean-text 0)))))
    (testing "clean counts + non-zero exit -> fail, reflected as an error"
      (let [r (verify/parse-test-output clean-text 1)]
        (is (false? (:passed? r)))
        (is (pos? (:error-count r)) "a non-zero exit must surface as at least one error")))
    (testing "'nothing to test' + zero exit -> pass (legitimately no tests)"
      (let [r (verify/parse-test-output "No changed bricks to test" 0)]
        (is (true? (:passed? r)))
        (is (true? (:no-tests? r)))))
    (testing "'nothing to test' + non-zero exit -> fail, reflected as an error (not '0 errors')"
      (let [r (verify/parse-test-output "nothing to test" 1)]
        (is (false? (:passed? r)))
        (is (= 1 (:error-count r)) "a failed run must not report 0 errors")))))

;------------------------------------------------------------------------------ Leave-verify redirect suppression tests (PR #288)
(defn ^{:stratum 0} make-leave-ctx
  "Build a minimal context suitable for leave-verify."
  [result on-fail]
  (cond-> {:phase {:started-at (- (System/currentTimeMillis) 1000)
                   :result result
                   :iterations 1}
           :execution {:phases-completed []}
           :execution/metrics {:tokens 0 :duration-ms 0}}
    on-fail (assoc :phase-config {:on-fail on-fail})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-passing-tests [body-fn]
  (with-redefs-fn
    {run-tests-var (fn [_ & _opts] {:passed? true :test-count 5 :assertion-count 10
                                    :fail-count 0 :error-count 0
                                    :output "Ran 5 tests containing 10 assertions.\n0 failures, 0 errors."})}
    body-fn))

(defn ^{:stratum 1} with-failing-tests [body-fn]
  (with-redefs-fn
    {run-tests-var (fn [_ & _opts] {:passed? false :test-count 3 :assertion-count 6
                                    :fail-count 2 :error-count 1
                                    :output "Ran 3 tests.\n2 failures, 1 error."})}
    body-fn))

(deftest ^{:stratum 1} verify-with-missing-environment-id-test
  (testing "verify phase fails fast when no execution environment-id is in context"
    (let [ctx (-> (create-base-context)
                  (dissoc :execution/environment-id)
                  (assoc :phase-config {:phase :verify}))
          interceptor (phase/get-phase-interceptor {:phase :verify})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Verify phase has no execution environment"
                            ((:enter interceptor) ctx))
          "Verify should throw when no execution environment is available"))))

(deftest ^{:stratum 1} verify-handles-test-runner-error-gracefully-test
  (testing "verify phase handles test runner exception as test failure"
    (with-redefs-fn
      {run-tests-var (fn [_ & _opts] {:passed? false :test-count 0 :fail-count 0 :error-count 1
                                      :output "bb: command not found"})}
      (fn []
        (let [ctx (-> (create-base-context)
                      (assoc :phase-config {:phase :verify}))
              interceptor (phase/get-phase-interceptor {:phase :verify})
              result ((:enter interceptor) ctx)]
          (is (= :error (get-in result [:phase :result :status]))
              "Runner error should produce :error result")
          (is (some? (get-in result [:phase :result :metrics :test-output]))
              "Test output captured in metrics even on runner error"))))))

(deftest ^{:stratum 1} verify-preserves-unparseable-test-output-test
  (testing "unparseable test output is surfaced as actionable verify feedback"
    (with-redefs-fn
      {run-tests-var (fn [_ & _opts] {:passed? false
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
          (is (str/includes? message "Syntax error compiling")))))))

(deftest ^{:stratum 1} verify-bounds-unparseable-output-preview-test
  (testing "long unparseable output is bounded in the verify error message"
    (let [preview-limit @#'verify/verify-error-preview-limit
          suffix @#'verify/truncated-output-suffix
          output (apply str (repeat (+ preview-limit 50) "x"))]
      (with-redefs-fn
        {run-tests-var (fn [_ & _opts] {:passed? false
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

(deftest ^{:stratum 1} leave-verify-normal-failure-emits-repair-requested-verdict-test
  (testing "Phase 3: normal verify failure with :on-fail configured sets
            verdict :repair-requested on the phase result. FSM's
            guarded :phase/fail array dispatches to on-fail :implement
            from there."
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "Tests failed: 3 assertions"}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (= :repair-requested (get-in result [:phase :verdict]))
          "verdict :repair-requested drives FSM on-fail redirect")
      (is (= :repair-requested (get-in result [:phase :result :output :phase/verdict]))
          ":phase/verdict on the result is what determine-phase-event reads")
      (is (phase/failed? (get result :phase))))))

(deftest ^{:stratum 1} leave-verify-parse-error-with-provider-words-emits-repair-requested-test
  (testing "parse-error previews are actionable even when test output
            contains provider-like fragments. Verdict :repair-requested
            because the failure isn't a real timeout/rate-limit."
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message (str (messages/t :verify/output-unparseable)
                                                     "\nHTTP 429 from project test output timed out")}
                               :metrics {:parse-error? true}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (= :repair-requested (get-in result [:phase :verdict])))
      (is (phase/failed? (get result :phase))))))

(deftest ^{:stratum 1} leave-verify-emits-verify-timeout-verdict-test
  (testing "Phase 3: timeout error sets verdict :verify/timeout. The
            FSM's :verdict/terminal? guard routes :phase/fail straight
            to :failed, bypassing :on-fail :implement — retrying the
            implementer wouldn't unblock a hung test process. This was
            the 2026-05-28 dogfood's biggest token sink."
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "Agent timed out after 600000ms"}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (= :verify/timeout (get-in result [:phase :verdict])))
      (is (= :verify/timeout (get-in result [:phase :result :output :phase/verdict])))
      (is (phase/failed? (get result :phase)))
      (is (true? (get-in result [:phase :error :timeout?]))
          "legacy :timeout? flag still in the error map (Phase 4 removes)"))))

(deftest ^{:stratum 1} leave-verify-emits-verify-rate-limited-verdict-test
  (testing "Phase 3: rate-limit error sets verdict :verify/rate-limited.
            FSM terminates rather than redirecting to implement —
            retrying the implementer doesn't change the provider quota."
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "429 rate limit exceeded"}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (= :verify/rate-limited (get-in result [:phase :verdict])))
      (is (phase/failed? (get result :phase)))
      (is (some? (get-in result [:phase :error :rate-limited?]))
          "legacy :rate-limited? flag still in error map until Phase 4"))

    (testing "rate-limit variant: 'you've hit your limit'"
      (let [ctx (make-leave-ctx {:status :error
                                 :error {:message "You've hit your limit · resets 7pm"}}
                                :implement)
            result (verify/leave-verify ctx)]
        (is (= :verify/rate-limited (get-in result [:phase :verdict])))))))

(deftest ^{:stratum 1} leave-verify-emits-exhausted-when-no-on-fail-test
  (testing "Phase 3: failed verify without :on-fail set emits verdict
            :exhausted. FSM has no redirect target to take — the
            guarded array's third branch is absent at compile time."
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "Tests failed"}}
                              nil)
          result (verify/leave-verify ctx)]
      (is (= :exhausted (get-in result [:phase :verdict])))
      (is (phase/failed? (get result :phase))))))

;------------------------------------------------------------------------------ Layer 2

;------------------------------------------------------------------------------ Enter Tests
(deftest ^{:stratum 2} verify-succeeds-when-tests-pass-test
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

(deftest ^{:stratum 2} verify-fails-when-tests-fail-test
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

(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (try
      (binding [loader/phase-loader-config-resource phase-test-config-resource]
        (f))
      (finally
        (phase/reset-phase-loader!)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.verify-failure-modes-test)
  :leave-this-here)
