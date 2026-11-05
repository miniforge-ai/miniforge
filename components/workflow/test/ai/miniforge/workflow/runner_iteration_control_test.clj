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
(ns ai.miniforge.workflow.runner-iteration-control-test
  "Operator control around the runner's phase step: a run paused and then
   cancelled must leave the pause wait and take the stop path, and a cancel
   during a phase interrupts the phase instead of waiting it out."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [ai.miniforge.workflow.checkpoint-test-support :as checkpoint-test-support]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.workflow.monitoring :as monitoring]
   [ai.miniforge.workflow.phase-test-support :as phase-test-support]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.workflow.runner-defaults :as defaults]
   [ai.miniforge.workflow.runner-phase-step :as phase-step]))

(use-fixtures :each
  phase-test-support/with-workflow-phase-test-support
  checkpoint-test-support/with-temp-checkpoint-root)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private stop-wait-ms
  "Bound on how long the test waits for the iteration to return after the
   stop. Many poll intervals, so only a wait that ignores :stopped misses it."
  5000)

(def ^{:stratum 0} ^:private blocked-phase-ms
  "How long a stubbed phase blocks: far past `stop-wait-ms`, so only an
   interrupt ends it in time."
  600000)

(defn- ^{:stratum 0} paused-control-state
  []
  (atom {:paused true :stopped false :adjustments {}}))

(defn- ^{:stratum 0} running-control-state
  []
  (atom {:paused false :stopped false :adjustments {}}))

(defn- ^{:stratum 0} iterate-and-report-interrupt
  "Run one iteration on this thread; return [result interrupted?], the
   second being whether the thread's interrupt flag is still set after."
  [context control-state]
  (let [result (runner/execute-single-iteration [] context {} 1 control-state)]
    [result (Thread/interrupted)]))

(defn- ^{:stratum 0} test-context
  []
  (ctx/create-context {:workflow/id :test
                       :workflow/version "1.0.0"
                       :workflow/pipeline [{:phase phase-test-support/runner-test-done}]}
                      {:task "Test"}
                      {}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} paused-then-stopped-iteration-takes-stop-path-test
  (testing "a stop issued during a pause ends the wait without a resume"
    (with-redefs [defaults/pause-poll-interval-ms (constantly 10)]
      (let [control-state (paused-control-state)
            iteration (future
                        (runner/execute-single-iteration
                         [] (test-context) {} 1 control-state))]
        (try
          ;; Let the iteration enter the pause wait before the stop lands.
          (Thread/sleep 100)
          (is (not (realized? iteration)) "the pause holds the iteration")
          (swap! control-state assoc :stopped true)
          (let [result (deref iteration stop-wait-ms ::still-waiting)]
            (is (not= ::still-waiting result)
                "the wait ends on :stopped while :paused is still true")
            (when (map? result)
              (is (= :failed (:execution/status result)))
              (is (some #(= :dashboard-stop (:type %))
                        (:execution/errors result)))))
          (finally
            ;; Release a wait that ignored :stopped so no thread outlives the test.
            (swap! control-state assoc :paused false)
            (deref iteration stop-wait-ms nil)))))))

(deftest ^{:stratum 1} cancel-interrupts-a-phase-in-flight-test
  (testing "a cancel while a phase runs interrupts it and stops the run,
            leaving no interrupt behind on the runner thread"
    (let [control-state (running-control-state)
          started       (promise)]
      (with-redefs [monitoring/check-workflow-supervision (constantly {:status :ok})
                    phase-step/execute-phase (fn [_ _ _]
                                               (deliver started true)
                                               (Thread/sleep blocked-phase-ms))]
        (let [iteration (future (iterate-and-report-interrupt (test-context) control-state))]
          (try
            (deref started stop-wait-ms nil)
            (swap! control-state assoc :stopped true)
            (let [[result interrupted?] (deref iteration stop-wait-ms [::still-running])]
              (is (not= ::still-running result) "the phase is interrupted, not waited out")
              (is (= :failed (:execution/status result)))
              (is (some #(= :dashboard-stop (:type %)) (:execution/errors result)))
              (is (false? interrupted?) "the runner thread's interrupt flag is cleared"))
            (finally
              (future-cancel iteration))))))))

(deftest ^{:stratum 1} finished-phase-leaves-no-cancel-watch-test
  (testing "after a phase returns, a later cancel does not interrupt the
            runner thread"
    (let [control-state (running-control-state)]
      (with-redefs [monitoring/check-workflow-supervision (constantly {:status :ok})
                    phase-step/execute-phase (fn [_ context _] context)]
        (let [result      @(future
                             (let [[result interrupted?] (iterate-and-report-interrupt
                                                          (test-context) control-state)]
                               (swap! control-state assoc :stopped true)
                               {:result result
                                :interrupted-during? interrupted?
                                :interrupted-after? (Thread/interrupted)}))]
          (is (map? (:result result)))
          (is (false? (:interrupted-during? result)))
          (is (false? (:interrupted-after? result))))))))

(deftest ^{:stratum 1} swallowed-interrupt-is-sent-again-test
  (testing "a phase that swallows the cancel's interrupt and blocks again
            (a fallback agent call) is interrupted again"
    (let [control-state (running-control-state)
          started       (promise)]
      (with-redefs [monitoring/check-workflow-supervision (constantly {:status :ok})
                    phase-step/execute-phase (fn [_ _ _]
                                               (deliver started true)
                                               (try
                                                 (Thread/sleep blocked-phase-ms)
                                                 (catch InterruptedException _ nil))
                                               (Thread/sleep blocked-phase-ms))]
        (let [iteration (future (iterate-and-report-interrupt (test-context) control-state))]
          (try
            (deref started stop-wait-ms nil)
            (swap! control-state assoc :stopped true)
            (let [[result interrupted?] (deref iteration stop-wait-ms [::still-running])]
              (is (not= ::still-running result) "the second wait is interrupted too")
              (is (= :failed (:execution/status result)))
              (is (false? interrupted?)))
            (finally
              (future-cancel iteration))))))))

(deftest ^{:stratum 1} fatal-error-during-cancel-is-rethrown-test
  (testing "an Error a phase throws after the cancel is not read as a stop"
    (let [control-state (running-control-state)
          started       (promise)]
      (with-redefs [monitoring/check-workflow-supervision (constantly {:status :ok})
                    phase-step/execute-phase (fn [_ _ _]
                                               (deliver started true)
                                               (try
                                                 (Thread/sleep blocked-phase-ms)
                                                 (catch InterruptedException e
                                                   (throw (ex-info "phase failed" {}
                                                                   (StackOverflowError. (ex-message e)))))))]
        (let [iteration (future
                          (try
                            (runner/execute-single-iteration [] (test-context) {} 1 control-state)
                            ::returned
                            (catch Throwable t t)))]
          (deref started stop-wait-ms nil)
          (swap! control-state assoc :stopped true)
          (let [outcome (deref iteration stop-wait-ms ::still-running)]
            (is (instance? Throwable outcome) "the failure propagates")
            (is (instance? StackOverflowError (ex-cause outcome)))))))))
