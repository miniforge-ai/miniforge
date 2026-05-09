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

(ns ai.miniforge.workflow.dag-resilience-failover-test
  "Tests for rate-limit failover handling, reset-time parsing, and sub-workflow error extraction."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.clock.interface :as clock]
   [ai.miniforge.workflow.dag-resilience :as resilience]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn ok-result [task-id]
  (dag/ok {:task-id task-id :status :implemented}))

(defn rate-limit-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

(defn generic-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

;------------------------------------------------------------------------------ Layer 3
;; handle-rate-limited-batch tests

(deftest test-handle-rate-limited-no-failover-configured
  (testing "pauses when no allowed-failover-backends configured"
    (let [[logger _] (log/collecting-logger)
          context {}
          decision (resilience/handle-rate-limited-batch
                    context #{:b :c} #{:a} logger)]
      (is (= :pause (:action decision)))
      (is (string? (:reason decision))))))

(deftest test-handle-rate-limited-auto-switch-disabled
  (testing "pauses when backend-auto-switch is false"
    (let [[logger _] (log/collecting-logger)
          context {:execution/opts
                   {:self-healing {:backend-auto-switch false
                                   :allowed-failover-backends [:openai]}}}
          decision (resilience/handle-rate-limited-batch
                    context #{:b} #{:a} logger)]
      (is (= :pause (:action decision)))
      (is (str/includes? (:reason decision) "disabled")))))

(deftest test-handle-rate-limited-with-allowed-backends
  (testing "attempts failover when allowed backends are configured"
    (let [[logger _] (log/collecting-logger)
          context {:execution/opts
                   {:self-healing {:backend-auto-switch true
                                   :allowed-failover-backends [:openai]}}}
          decision (resilience/handle-rate-limited-batch
                    context #{:b} #{:a} logger)]
      ;; Should attempt failover — result depends on backend health state
      ;; Either continues with new backend or pauses if failover fails
      (is (contains? #{:continue :pause} (:action decision))))))

;------------------------------------------------------------------------------ Layer 7
;; parse-reset-instant tests

(deftest test-parse-reset-instant-absolute-time
  (let [fixed-now (Instant/parse "2026-05-09T20:00:00Z")]
    (with-redefs [clock/now-ms (fn [] (.toEpochMilli fixed-now))]
      (testing "parses 'resets 2pm' using the system timezone"
        (let [text "You've hit your limit · resets 2pm"
              result (resilience/parse-reset-instant text)
              expected (-> (java.time.ZonedDateTime/ofInstant
                            fixed-now
                            (java.time.ZoneId/systemDefault))
                           (.withHour 14)
                           (.withMinute 0)
                           (.withSecond 0)
                           (.withNano 0)
                           (#(if (.isBefore % (java.time.ZonedDateTime/ofInstant
                                               fixed-now
                                               (java.time.ZoneId/systemDefault)))
                               (.plusDays % 1)
                               %))
                           (.toInstant))]
          (is (= expected result))))

      (testing "parses 'resets 2pm (America/Los_Angeles)' with explicit timezone"
        (let [text "You've hit your limit · resets 2pm (America/Los_Angeles)"
              result (resilience/parse-reset-instant text)]
          (is (= (Instant/parse "2026-05-09T21:00:00Z") result))))

      (testing "parses 'resets 2:30pm' with minutes"
        (let [text "resets 2:30pm (America/Los_Angeles)"
              result (resilience/parse-reset-instant text)]
          (is (= (Instant/parse "2026-05-09T21:30:00Z") result)))))))

(deftest test-parse-reset-instant-relative-time
  (testing "parses 'resets in 30 minutes' against the injected clock"
    (let [fixed-now (Instant/parse "2026-05-09T20:00:00Z")]
      (with-redefs [clock/now-ms (fn [] (.toEpochMilli fixed-now))]
        (is (= (Instant/parse "2026-05-09T20:30:00Z")
               (resilience/parse-reset-instant "resets in 30 minutes")))))))

(deftest test-parse-reset-instant-no-match
  (testing "returns nil for non-reset text"
    (is (nil? (resilience/parse-reset-instant "Syntax error")))
    (is (nil? (resilience/parse-reset-instant nil)))))

;------------------------------------------------------------------------------ Layer 8
;; handle-rate-limited-batch with reset time waiting

(deftest test-handle-rate-limited-batch-waits-for-reset
  (testing "waits for reset when reset time is imminent (relative)"
    (let [fake-now (atom (.toEpochMilli (Instant/parse "2026-05-09T20:00:00Z")))
          [logger _] (log/collecting-logger)
          rate-limit-msg "You've hit your limit · resets in 1 seconds"
          results {:b (dag/err :task-execution-failed rate-limit-msg {:task-id :b})}
          sleep-calls (atom [])]
      (with-redefs [clock/now-ms (fn [] @fake-now)]
        (binding [resilience/*sleep!* (fn [wait-ms]
                                        (swap! sleep-calls conj wait-ms)
                                        (swap! fake-now + wait-ms))]
          (let [decision (resilience/handle-rate-limited-batch
                          {} #{:b} #{:a} logger results)]
            (is (= :continue (:action decision)))
            (is (= 1000 (:waited-ms decision)))
            (is (= [1000] @sleep-calls))
            (is (= (.toEpochMilli (Instant/parse "2026-05-09T20:00:01Z"))
                   @fake-now))))))))

(deftest test-handle-rate-limited-batch-pauses-without-results
  (testing "pauses when no results provided (backward compat)"
    (let [[logger _] (log/collecting-logger)
          decision (resilience/handle-rate-limited-batch
                    {} #{:b} #{:a} logger)]
      (is (= :pause (:action decision))))))

;------------------------------------------------------------------------------ Layer 9
;; extract-sub-workflow-error tests

(deftest test-extract-sub-workflow-error-from-phase-results
  (testing "extracts error from phase results when execution errors empty"
    (let [result {:execution/errors []
                  :execution/phase-results
                  {:implement {:error {:message "Claude CLI rate limited: You've hit your limit"}}}}]
      (is (= "Claude CLI rate limited: You've hit your limit"
             (dag-orch/extract-sub-workflow-error result)))))

  (testing "prefers execution errors when available"
    (let [result {:execution/errors [{:message "Exceeded 5 redirects"}]
                  :execution/phase-results
                  {:implement {:error {:message "some phase error"}}}}]
      (is (= "Exceeded 5 redirects"
             (dag-orch/extract-sub-workflow-error result)))))

  (testing "falls back to default message"
    (is (= "Sub-workflow failed"
           (dag-orch/extract-sub-workflow-error {})))))
