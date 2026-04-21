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
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-software-factory.verify :as verify]))

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
  (testing "normal verify failure with :on-fail configured sets :redirect-to"
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "Tests failed: 3 assertions"}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (= :implement (get-in result [:phase :redirect-to])))
      (is (= :failed (get-in result [:phase :status]))))))

(deftest leave-verify-no-redirect-on-timeout-test
  (testing "timeout error does NOT redirect even with :on-fail configured"
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "Agent timed out after 600000ms"}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (nil? (get-in result [:phase :redirect-to])))
      (is (= :failed (get-in result [:phase :status])))
      (is (true? (get-in result [:phase :error :timeout?]))))))

(deftest leave-verify-no-redirect-on-rate-limit-test
  (testing "rate-limit error does NOT redirect even with :on-fail configured"
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "429 rate limit exceeded"}}
                              :implement)
          result (verify/leave-verify ctx)]
      (is (nil? (get-in result [:phase :redirect-to])))
      (is (= :failed (get-in result [:phase :status])))
      (is (some? (get-in result [:phase :error :rate-limited?]))))

    (testing "rate-limit variant: you've hit your limit"
      (let [ctx (make-leave-ctx {:status :error
                                 :error {:message "You've hit your limit · resets 7pm"}}
                                :implement)
            result (verify/leave-verify ctx)]
        (is (nil? (get-in result [:phase :redirect-to])))))))

(deftest leave-verify-no-redirect-without-on-fail-test
  (testing "normal failure without :on-fail does not set :redirect-to"
    (let [ctx (make-leave-ctx {:status :error
                               :error {:message "Tests failed"}}
                              nil)
          result (verify/leave-verify ctx)]
      (is (nil? (get-in result [:phase :redirect-to])))
      (is (= :failed (get-in result [:phase :status]))))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.verify-failure-modes-test)
  :leave-this-here)
