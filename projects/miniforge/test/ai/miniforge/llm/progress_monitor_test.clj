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

(ns ai.miniforge.llm.progress-monitor-test
  "Tests for adaptive timeout and progress monitoring."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.llm.progress-monitor :as pm]))

(deftest create-progress-monitor-test
  (testing "creates monitor with default settings"
    (let [monitor (pm/create-progress-monitor {})]
      (is (some? @monitor))
      (is (= 120000 (:stagnation-threshold-ms @monitor)))
      (is (= 600000 (:max-total-ms @monitor)))))

  (testing "creates monitor with custom settings"
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 60000
                    :max-total-ms 300000})]
      (is (= 60000 (:stagnation-threshold-ms @monitor)))
      (is (= 300000 (:max-total-ms @monitor))))))

(deftest record-chunk-test
  (testing "records meaningful chunk as progress"
    (let [monitor (pm/create-progress-monitor {})]
      (is (true? (pm/record-chunk! monitor "Implementing the feature...")))
      (is (= 1 (:chunk-count @monitor)))
      (is (= 1 (count (:unique-chunks @monitor))))))

  (testing "detects stagnant 'thinking' messages"
    (let [monitor (pm/create-progress-monitor {})]
      (pm/record-chunk! monitor "thinking")
      (is (false? (pm/record-chunk! monitor "thinking")))
      (is (pos? (:stagnant-cycles @monitor)))))

  (testing "detects repeated identical content"
    (let [monitor (pm/create-progress-monitor {})]
      (pm/record-chunk! monitor "Processing...")
      (Thread/sleep 10)  ; Small delay to ensure different timestamps
      (is (false? (pm/record-chunk! monitor "Processing...")))))

  (testing "accepts new unique content as progress"
    (let [monitor (pm/create-progress-monitor
                   {:min-activity-interval-ms 10})]  ; Short interval for testing
      (pm/record-chunk! monitor "Step 1 complete")
      (Thread/sleep 15)  ; Just over the interval
      (is (true? (pm/record-chunk! monitor "Step 2 starting")))
      (is (= 2 (count (:unique-chunks @monitor)))))))

(deftest record-file-write-test
  (testing "records file write as significant progress"
    (let [monitor (pm/create-progress-monitor {})]
      (pm/record-file-write! monitor "src/foo.clj")
      (is (= 1 (count (:file-writes @monitor))))
      (is (contains? (:file-writes @monitor) "src/foo.clj"))))

  (testing "file write resets stagnation"
    (let [monitor (pm/create-progress-monitor {})]
      ;; Create stagnation
      (swap! monitor assoc :stagnant-cycles 5)
      ;; File write should reset it
      (pm/record-file-write! monitor "src/bar.clj")
      (is (zero? (:stagnant-cycles @monitor))))))

(deftest check-timeout-test
  (testing "returns nil when making progress"
    (let [monitor (pm/create-progress-monitor {})]
      (pm/record-chunk! monitor "Working...")
      (is (nil? (pm/check-timeout monitor)))))

  (testing "detects stagnation timeout"
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 100})]  ; 100ms threshold
      ;; Set last activity to past
      (swap! monitor assoc :last-activity-at
             (- (System/currentTimeMillis) 150))
      (let [timeout (pm/check-timeout monitor)]
        (is (some? timeout))
        (is (= :stagnation (:type timeout)))
        (is (>= (:elapsed-ms timeout) 150)))))

  (testing "detects hard limit timeout"
    (let [monitor (pm/create-progress-monitor
                   {:max-total-ms 100})]  ; 100ms max
      ;; Set start time to past
      (swap! monitor assoc :started-at
             (- (System/currentTimeMillis) 150))
      (let [timeout (pm/check-timeout monitor)]
        (is (some? timeout))
        (is (= :hard-limit (:type timeout)))
        (is (>= (:elapsed-ms timeout) 150)))))

  (testing "stagnation takes precedence over hard limit check"
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 50
                    :max-total-ms 200})]
      ;; Both conditions met, but hard limit comes first in elapsed time
      (swap! monitor assoc
             :started-at (- (System/currentTimeMillis) 250)
             :last-activity-at (- (System/currentTimeMillis) 100))
      (let [timeout (pm/check-timeout monitor)]
        (is (some? timeout))
        ;; Should return hard-limit since total time exceeded
        (is (= :hard-limit (:type timeout)))))))

(deftest get-stats-test
  (testing "returns current statistics"
    (let [monitor (pm/create-progress-monitor
                   {:min-activity-interval-ms 5})]  ; Very short interval
      (pm/record-chunk! monitor "First meaningful chunk")
      (Thread/sleep 20)  ; Ensure sufficient time
      (pm/record-chunk! monitor "Second meaningful chunk")
      (pm/record-file-write! monitor "file.clj")
      (let [stats (pm/get-stats monitor)]
        (is (number? (:total-elapsed-ms stats)))
        (is (number? (:time-since-activity-ms stats)))
        (is (= 2 (:chunks stats)))
        (is (= 2 (:unique-chunks stats)))
        (is (= 1 (:files-written stats)))
        (is (true? (:is-active? stats))))))

  (testing "detects inactive state"
    (let [monitor (pm/create-progress-monitor {})]
      ;; Set last activity to 40 seconds ago
      (swap! monitor assoc :last-activity-at
             (- (System/currentTimeMillis) 40000))
      (let [stats (pm/get-stats monitor)]
        (is (false? (:is-active? stats)))))))

(deftest adaptive-timeout-scenario-test
  (testing "realistic workflow scenario"
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 60000   ; 1 minute
                    :max-total-ms 300000              ; 5 minutes
                    :min-activity-interval-ms 10})]  ; Short interval for testing

      ;; Phase 1: Active generation
      (pm/record-chunk! monitor "Analyzing requirements...")
      (Thread/sleep 15)
      (pm/record-chunk! monitor "Creating plan...")
      (Thread/sleep 15)
      (pm/record-file-write! monitor "plan.edn")

      ;; Should not timeout yet
      (is (nil? (pm/check-timeout monitor)))

      ;; Phase 2: Some thinking (not counting as progress)
      (pm/record-chunk! monitor "thinking")
      (pm/record-chunk! monitor "thinking")
      (is (pos? (:stagnant-cycles @monitor)))

      ;; Phase 3: Resume progress
      (Thread/sleep 15)
      (pm/record-chunk! monitor "Implementing feature...")
      (pm/record-file-write! monitor "impl.clj")

      ;; Stagnation should be reset
      (is (zero? (:stagnant-cycles @monitor)))

      ;; Final stats
      (let [stats (pm/get-stats monitor)]
        (is (= 5 (:chunks stats)))  ; 5 total chunks (including "thinking" ones)
        (is (= 2 (:files-written stats)))
        (is (true? (:is-active? stats)))))))
