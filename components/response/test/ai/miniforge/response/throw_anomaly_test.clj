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

;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.response.throw-anomaly-test
  "Tests for throw-anomaly! and slingshot integration."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.response.anomaly :as anomaly]
   [slingshot.slingshot :refer [try+]]))

;; ============================================================================
;; throw-anomaly! basic behavior
;; ============================================================================

(deftest throw-anomaly-produces-catchable-map-test
  (testing "thrown anomaly is caught by :anomaly/category selector"
    (let [result (try+
                   (anomaly/throw-anomaly! :anomalies/fault "something broke")
                   (catch [:anomaly/category :anomalies/fault] m
                     {:caught true :message (:anomaly/message m)}))]
      (is (true? (:caught result)))
      (is (= "something broke" (:message result))))))

(deftest throw-anomaly-includes-context-test
  (testing "context keys are available for destructuring"
    (let [result (try+
                   (anomaly/throw-anomaly! :anomalies.llm/rate-limited "Rate limit"
                                           {:anomaly.llm/backend :anthropic
                                            :anomaly.llm/status 429})
                   (catch [:anomaly/category :anomalies.llm/rate-limited]
                          {:keys [anomaly.llm/backend anomaly.llm/status]}
                     {:backend backend :status status}))]
      (is (= :anthropic (:backend result)))
      (is (= 429 (:status result))))))

(deftest throw-anomaly-has-canonical-keys-test
  (testing "thrown map always has :anomaly/id, :anomaly/timestamp, :anomaly/category"
    (let [result (try+
                   (anomaly/throw-anomaly! :anomalies/timeout "timed out")
                   (catch [:anomaly/category :anomalies/timeout] m m))]
      (is (uuid? (:anomaly/id result)))
      (is (inst? (:anomaly/timestamp result)))
      (is (= :anomalies/timeout (:anomaly/category result))))))

(deftest throw-anomaly-non-matching-falls-through-test
  (testing "non-matching category falls through to catch Object"
    (let [result (try+
                   (anomaly/throw-anomaly! :anomalies/forbidden "denied")
                   (catch [:anomaly/category :anomalies/fault] _
                     :wrong)
                   (catch Object obj
                     {:fell-through true :category (:anomaly/category obj)}))]
      (is (true? (:fell-through result)))
      (is (= :anomalies/forbidden (:category result))))))

;; ============================================================================
;; Domain-specific constructors
;; ============================================================================

(deftest llm-anomaly-constructor-test
  (testing "llm-anomaly includes backend"
    (let [a (anomaly/llm-anomaly :anomalies.llm/rate-limited "slow down" :anthropic
                                 {:anomaly.llm/status 429})]
      (is (= :anomalies.llm/rate-limited (:anomaly/category a)))
      (is (= :anthropic (:anomaly.llm/backend a)))
      (is (= 429 (:anomaly.llm/status a))))))

(deftest executor-anomaly-constructor-test
  (testing "executor-anomaly includes mode"
    (let [a (anomaly/executor-anomaly :anomalies.executor/unavailable "no docker" :governed)]
      (is (= :anomalies.executor/unavailable (:anomaly/category a)))
      (is (= :governed (:anomaly.executor/mode a))))))

;; ============================================================================
;; Taxonomy coverage
;; ============================================================================

(deftest new-anomaly-types-registered-test
  (testing "all new anomaly types are in all-anomalies"
    (doseq [a [:anomalies.llm/rate-limited
               :anomalies.llm/context-exceeded
               :anomalies.llm/timeout
               :anomalies.llm/unavailable
               :anomalies.executor/unavailable
               :anomalies.executor/timeout
               :anomalies.executor/acquisition-failed
               :anomalies.dashboard/stop
               :anomalies.agent/validation-failed]]
      (is (anomaly/anomaly? a) (str a " should be registered")))))

(deftest anomaly-category-covers-new-types-test
  (testing "anomaly-category returns correct group for new types"
    (is (= :llm (anomaly/anomaly-category :anomalies.llm/rate-limited)))
    (is (= :executor (anomaly/anomaly-category :anomalies.executor/unavailable)))
    (is (= :dashboard (anomaly/anomaly-category :anomalies.dashboard/stop)))))

(deftest retryable-covers-llm-anomalies-test
  (testing "LLM rate-limited and timeout are retryable"
    (is (true? (anomaly/retryable? :anomalies.llm/rate-limited)))
    (is (true? (anomaly/retryable? :anomalies.llm/timeout)))
    (is (true? (anomaly/retryable? :anomalies.llm/unavailable)))))

;; ============================================================================
;; End-to-end: throw + catch with domain constructor
;; ============================================================================

(deftest throw-llm-anomaly-end-to-end-test
  (testing "llm-anomaly thrown via throw-anomaly! is fully destructurable"
    (let [result (try+
                   (anomaly/throw-anomaly!
                    :anomalies.llm/rate-limited "Rate limit hit"
                    {:anomaly.llm/backend :anthropic
                     :anomaly.llm/retry-after-ms 30000})
                   (catch [:anomaly/category :anomalies.llm/rate-limited]
                          {:keys [anomaly/message anomaly.llm/backend anomaly.llm/retry-after-ms]}
                     {:message message :backend backend :retry-after-ms retry-after-ms}))]
      (is (= "Rate limit hit" (:message result)))
      (is (= :anthropic (:backend result)))
      (is (= 30000 (:retry-after-ms result))))))
