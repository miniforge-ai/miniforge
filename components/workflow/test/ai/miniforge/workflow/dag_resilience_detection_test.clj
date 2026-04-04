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

(ns ai.miniforge.workflow.dag-resilience-detection-test
  "Tests for rate-limit detection: rate-limit-error?, analyze-batch, rate-limit-in-text?."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.workflow.dag-resilience :as resilience]
   [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn ok-result [task-id]
  (dag/ok {:task-id task-id :status :implemented}))

(defn rate-limit-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

(defn generic-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

;------------------------------------------------------------------------------ Layer 1
;; rate-limit-error? tests

(deftest test-rate-limit-error-detects-claude-cli
  (testing "detects Claude CLI rate limit message"
    (is (resilience/rate-limit-error?
         (rate-limit-err "You've hit your limit · resets 2pm")))))

(deftest test-rate-limit-error-detects-generic
  (testing "detects generic rate limit messages"
    (are [msg] (resilience/rate-limit-error? (rate-limit-err msg))
      "API rate limit exceeded"
      "Rate limit reached for model"
      "rate-limit error on request"
      "Quota exceeded for this billing period"
      "429 Too Many Requests"
      "Too Many Requests"
      "resets 2pm"
      "resets in 30 minutes")))

(deftest test-rate-limit-error-rejects-non-rate-limit
  (testing "does not flag non-rate-limit errors"
    (are [msg] (not (resilience/rate-limit-error? (generic-err msg)))
      "Syntax error in foo.clj"
      "Connection refused"
      "Task failed: compilation error"
      "File not found")))

(deftest test-rate-limit-error-returns-false-for-ok
  (testing "returns nil/false for successful results"
    (is (not (resilience/rate-limit-error? (ok-result :a))))))

(deftest test-rate-limit-error-handles-map-message
  (testing "handles non-string :message (error map) without throwing ClassCastException"
    (let [result (dag/err :task-execution-failed
                          {:type :phase-error :phase :implement
                           :message "Sub-workflow failed"
                           :data {:some "context"}}
                          {:task-id :test})]
      (is (not (resilience/rate-limit-error? result))))))

;------------------------------------------------------------------------------ Layer 2
;; analyze-batch-for-rate-limits tests

(deftest test-analyze-batch-all-ok
  (testing "all successful results"
    (let [results {:a (ok-result :a)
                   :b (ok-result :b)
                   :c (ok-result :c)}
          analysis (resilience/analyze-batch-for-rate-limits results)]
      (is (= #{:a :b :c} (:completed-ids analysis)))
      (is (empty? (:rate-limited-ids analysis)))
      (is (empty? (:other-failed-ids analysis))))))

(deftest test-analyze-batch-with-rate-limits
  (testing "mix of successful and rate-limited results"
    (let [results {:a (ok-result :a)
                   :b (rate-limit-err "You've hit your limit")
                   :c (rate-limit-err "429 Too Many Requests")}
          analysis (resilience/analyze-batch-for-rate-limits results)]
      (is (= #{:a} (:completed-ids analysis)))
      (is (= #{:b :c} (:rate-limited-ids analysis)))
      (is (empty? (:other-failed-ids analysis))))))

(deftest test-analyze-batch-mixed-failures
  (testing "mix of rate-limited and other failures"
    (let [results {:a (ok-result :a)
                   :b (rate-limit-err "Rate limit reached")
                   :c (generic-err "Syntax error in foo.clj")}
          analysis (resilience/analyze-batch-for-rate-limits results)]
      (is (= #{:a} (:completed-ids analysis)))
      (is (= #{:b} (:rate-limited-ids analysis)))
      (is (= #{:c} (:other-failed-ids analysis))))))

(deftest test-analyze-batch-empty
  (testing "empty results"
    (let [analysis (resilience/analyze-batch-for-rate-limits {})]
      (is (empty? (:completed-ids analysis)))
      (is (empty? (:rate-limited-ids analysis)))
      (is (empty? (:other-failed-ids analysis))))))

;------------------------------------------------------------------------------ Layer 6
;; rate-limit-in-text? tests

(deftest test-rate-limit-in-text-detects-patterns
  (testing "detects rate limit patterns in arbitrary text"
    (are [text] (resilience/rate-limit-in-text? text)
      "You've hit your limit · resets 2pm"
      "Claude CLI rate limited: You've hit your limit · resets 2pm (America/Los_Angeles)"
      "API rate limit exceeded"
      "429 Too Many Requests"
      "Quota exceeded for this billing period"
      "resets 3pm (US/Pacific)")))

(deftest test-rate-limit-in-text-rejects-normal-text
  (testing "does not flag normal text"
    (are [text] (not (resilience/rate-limit-in-text? text))
      "Syntax error in foo.clj"
      "Connection refused"
      nil
      "")))
