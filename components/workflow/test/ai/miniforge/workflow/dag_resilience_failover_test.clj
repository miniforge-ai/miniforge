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
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.self-healing.interface :as self-healing])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

;; Test fixtures
(defn ^{:stratum 0} ok-result [task-id]
  (dag/ok {:task-id task-id :status :implemented}))

(defn ^{:stratum 0} rate-limit-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

(defn ^{:stratum 0} generic-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

(def ^{:stratum 0} switch-cooldown-ms
  "Cooldown the switch double reports: the self-healing default, 30 minutes."
  (.toMillis (java.time.Duration/ofMinutes 30)))

(defn ^{:stratum 0} recording-backend-call
  "Stand-in for `self-healing/record-backend-call!` that appends
   [:record backend success?] to CALLS. The real one writes the backend
   health file under the developer's ~/.miniforge, which runs read when
   choosing a backend. Returns the backend's stats after its first call,
   the shape the real one returns."
  [calls]
  (fn [backend success?]
    (swap! calls conj [:record backend success?])
    (let [successful   (if success? 1 0)
          last-failure (when-not success? (str Instant/EPOCH))]
      {:total-calls      1
       :successful-calls successful
       :success-rate     (double successful)
       :last-failure     last-failure})))

;; handle-rate-limited-batch tests
(deftest ^{:stratum 0} test-handle-rate-limited-no-failover-configured
  (testing "pauses when no allowed-failover-backends configured"
    (let [[logger _] (log/collecting-logger)
          context {}
          decision (resilience/handle-rate-limited-batch
                    context #{:b :c} #{:a} logger)]
      (is (= :pause (:action decision)))
      (is (string? (:reason decision))))))

(deftest ^{:stratum 0} test-handle-rate-limited-auto-switch-disabled
  (testing "pauses when backend-auto-switch is false"
    (let [[logger _] (log/collecting-logger)
          context {:execution/opts
                   {:self-healing {:backend-auto-switch false
                                   :allowed-failover-backends [:openai]}}}
          decision (resilience/handle-rate-limited-batch
                    context #{:b} #{:a} logger)]
      (is (= :pause (:action decision)))
      (is (str/includes? (:reason decision) "disabled")))))

;; parse-reset-instant tests
(deftest ^{:stratum 0} test-parse-reset-instant-absolute-time
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

(deftest ^{:stratum 0} test-parse-reset-instant-relative-time
  (testing "parses 'resets in 30 minutes' against the injected clock"
    (let [fixed-now (Instant/parse "2026-05-09T20:00:00Z")]
      (with-redefs [clock/now-ms (fn [] (.toEpochMilli fixed-now))]
        (is (= (Instant/parse "2026-05-09T20:30:00Z")
               (resilience/parse-reset-instant "resets in 30 minutes")))))))

(deftest ^{:stratum 0} test-parse-reset-instant-no-match
  (testing "returns nil for non-reset text"
    (is (nil? (resilience/parse-reset-instant "Syntax error")))
    (is (nil? (resilience/parse-reset-instant nil)))))

;; handle-rate-limited-batch with reset time waiting
(deftest ^{:stratum 0} test-handle-rate-limited-batch-waits-for-reset
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

(deftest ^{:stratum 0} test-handle-rate-limited-batch-pauses-without-results
  (testing "pauses when no results provided (backward compat)"
    (let [[logger _] (log/collecting-logger)
          decision (resilience/handle-rate-limited-batch
                    {} #{:b} #{:a} logger)]
      (is (= :pause (:action decision))))))

;; extract-sub-workflow-error tests
(deftest ^{:stratum 0} test-extract-sub-workflow-error-from-phase-results
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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} recording-backend-switch
  "Stand-in for `self-healing/trigger-backend-switch!` that appends
   [:switch from to] to CALLS instead of persisting a cooldown and a new
   default backend. Returns the real one's
   {:from :to :cooldown-until <Instant> :cooldown-ms}."
  [calls]
  (fn [from to]
    (swap! calls conj [:switch from to])
    {:from           from
     :to             to
     :cooldown-until Instant/EPOCH
     :cooldown-ms    switch-cooldown-ms}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-handle-rate-limited-with-allowed-backends
  (testing "records the current backend's failure, then switches to the allowed one"
    (let [[logger _] (log/collecting-logger)
          calls (atom [])
          context {:execution/opts
                   {:self-healing {:backend-auto-switch true
                                   :allowed-failover-backends [:openai]}}}]
      (with-redefs [self-healing/record-backend-call!   (recording-backend-call calls)
                    self-healing/trigger-backend-switch! (recording-backend-switch calls)]
        (is (= {:action :continue :new-backend :openai}
               (resilience/handle-rate-limited-batch context #{:b} #{:a} logger)))
        (is (= [[:record :claude false] [:switch :claude :openai]] @calls))))))
