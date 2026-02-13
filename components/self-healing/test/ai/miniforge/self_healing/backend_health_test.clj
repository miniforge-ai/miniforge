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

(ns ai.miniforge.self-healing.backend-health-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.java.io :as io]
   [ai.miniforge.self-healing.backend-health :as health]))

;;------------------------------------------------------------------------------ Test fixtures

(def test-health-file
  "Test file path for backend health data"
  (str (System/getProperty "java.io.tmpdir") "/test_backend_health.edn"))

(defn cleanup-test-file
  "Delete test health file"
  []
  (when (.exists (io/file test-health-file))
    (.delete (io/file test-health-file))))

(defn with-test-file
  "Fixture to use test file and clean up after"
  [f]
  (with-redefs [health/backend-health-path (constantly test-health-file)]
    (cleanup-test-file)
    (try
      (f)
      (finally
        (cleanup-test-file)))))

(use-fixtures :each with-test-file)

;;------------------------------------------------------------------------------ Tests

(deftest test-load-save-roundtrip
  (testing "Load and save backend health data"
    (let [test-data {:backends {:anthropic {:total-calls 100
                                             :successful-calls 95
                                             :success-rate 0.95
                                             :last-failure nil}}
                     :switch-cooldowns {}
                     :default-backend :anthropic
                     :fallback-order [:anthropic :openai]}]
      (health/save-health! test-data)
      (let [loaded (health/load-health)]
        (is (= (:default-backend test-data) (:default-backend loaded)))
        (is (= (:fallback-order test-data) (:fallback-order loaded)))
        (is (= (get-in test-data [:backends :anthropic :total-calls])
               (get-in loaded [:backends :anthropic :total-calls])))))))

(deftest test-default-health-data
  (testing "Load returns default data when file doesn't exist"
    (cleanup-test-file)
    (let [health (health/load-health)]
      (is (= :anthropic (:default-backend health)))
      (is (seq (:fallback-order health)))
      (is (map? (:backends health)))
      (is (map? (:switch-cooldowns health))))))

(deftest test-record-backend-call-success
  (testing "Record successful backend call"
    (health/record-backend-call! :anthropic true)
    (let [rate (health/get-backend-success-rate :anthropic)]
      (is (= 1.0 rate)))

    (health/record-backend-call! :anthropic true)
    (let [rate (health/get-backend-success-rate :anthropic)]
      (is (= 1.0 rate)))))

(deftest test-record-backend-call-failure
  (testing "Record failed backend call"
    (health/record-backend-call! :openai false)
    (let [rate (health/get-backend-success-rate :openai)]
      (is (= 0.0 rate)))))

(deftest test-record-backend-call-mixed
  (testing "Record mix of successful and failed calls"
    ;; 8 successes, 2 failures = 80% success rate
    (dotimes [_ 8]
      (health/record-backend-call! :codex true))
    (dotimes [_ 2]
      (health/record-backend-call! :codex false))

    (let [rate (health/get-backend-success-rate :codex)]
      (is (= 0.8 rate)))))

(deftest test-success-rate-calculation
  (testing "Success rate updates correctly"
    (health/record-backend-call! :ollama true)
    (is (= 1.0 (health/get-backend-success-rate :ollama)))

    (health/record-backend-call! :ollama false)
    (is (= 0.5 (health/get-backend-success-rate :ollama)))

    (health/record-backend-call! :ollama false)
    (is (= (/ 1.0 3.0) (health/get-backend-success-rate :ollama)))))

(deftest test-should-switch-backend-threshold
  (testing "Should switch when below threshold"
    ;; 85% success rate, below 90% threshold
    (dotimes [_ 85]
      (health/record-backend-call! :anthropic true))
    (dotimes [_ 15]
      (health/record-backend-call! :anthropic false))

    (is (health/should-switch-backend? :anthropic 0.90))))

(deftest test-should-not-switch-backend-above-threshold
  (testing "Should not switch when above threshold"
    ;; 95% success rate, above 90% threshold
    (dotimes [_ 95]
      (health/record-backend-call! :openai true))
    (dotimes [_ 5]
      (health/record-backend-call! :openai false))

    (is (not (health/should-switch-backend? :openai 0.90)))))

(deftest test-should-not-switch-no-data
  (testing "Should not switch when no data available"
    (is (not (health/should-switch-backend? :newbackend 0.90)))))

(deftest test-cooldown-period
  (testing "Backend in cooldown after switch"
    (let [switch-result (health/trigger-backend-switch! :anthropic :openai 1800000)]
      (is (= :anthropic (:from switch-result)))
      (is (= :openai (:to switch-result)))
      (is (health/in-cooldown? :anthropic 1800000)))))

(deftest test-not-in-cooldown
  (testing "Backend not in cooldown when no recent switch"
    (is (not (health/in-cooldown? :anthropic 1800000)))))

(deftest test-cooldown-expires
  (testing "Cooldown expires after period"
    ;; Use very short cooldown for testing
    (health/trigger-backend-switch! :anthropic :openai 1)
    (is (health/in-cooldown? :anthropic 1))
    ;; Wait for cooldown to expire
    (Thread/sleep 2)
    (is (not (health/in-cooldown? :anthropic 1)))))

(deftest test-select-best-backend-basic
  (testing "Select best backend from fallback order"
    ;; No health data yet, should select first non-current backend
    (let [best (health/select-best-backend :anthropic 0.90 1800000)]
      (is (not= :anthropic best))
      (is (some? best)))))

(deftest test-select-best-backend-skip-unhealthy
  (testing "Skip unhealthy backends in selection"
    ;; Make openai unhealthy (50% success rate)
    (dotimes [_ 50]
      (health/record-backend-call! :openai true))
    (dotimes [_ 50]
      (health/record-backend-call! :openai false))

    (let [best (health/select-best-backend :anthropic 0.90 1800000)]
      (is (not= :openai best))
      (is (not= :anthropic best)))))

(deftest test-select-best-backend-skip-cooldown
  (testing "Skip backends in cooldown"
    ;; Put openai in cooldown
    (health/trigger-backend-switch! :openai :anthropic 1800000)

    (let [best (health/select-best-backend :anthropic 0.90 1800000)]
      (is (not= :openai best))
      (is (not= :anthropic best)))))

(deftest test-trigger-backend-switch-updates-default
  (testing "Triggering switch updates default backend"
    (health/trigger-backend-switch! :anthropic :openai 1800000)
    (let [health-data (health/load-health)]
      (is (= :openai (:default-backend health-data))))))

(deftest test-check-and-switch-if-needed-no-switch
  (testing "No switch when backend is healthy"
    ;; 95% success rate
    (dotimes [_ 95]
      (health/record-backend-call! :anthropic true))
    (dotimes [_ 5]
      (health/record-backend-call! :anthropic false))

    (let [result (health/check-and-switch-if-needed :anthropic 0.90 1800000)]
      (is (nil? result)))))

(deftest test-check-and-switch-if-needed-triggers-switch
  (testing "Triggers switch when backend is unhealthy"
    ;; 85% success rate, below 90% threshold
    (dotimes [_ 85]
      (health/record-backend-call! :anthropic true))
    (dotimes [_ 15]
      (health/record-backend-call! :anthropic false))

    (let [result (health/check-and-switch-if-needed :anthropic 0.90 1800000)]
      (is (some? result))
      (is (:should-switch? result))
      (is (= :anthropic (:from result)))
      (is (not= :anthropic (:to result))))))

(deftest test-check-and-switch-respects-cooldown
  (testing "No switch when in cooldown"
    ;; Make anthropic unhealthy
    (dotimes [_ 80]
      (health/record-backend-call! :anthropic true))
    (dotimes [_ 20]
      (health/record-backend-call! :anthropic false))

    ;; Trigger initial switch
    (health/check-and-switch-if-needed :anthropic 0.90 1800000)

    ;; Try to switch again - should fail due to cooldown
    (let [result (health/check-and-switch-if-needed :anthropic 0.90 1800000)]
      (is (nil? result)))))

(deftest test-multiple-backends-tracking
  (testing "Track health for multiple backends independently"
    ;; Anthropic: 90% success
    (dotimes [_ 90]
      (health/record-backend-call! :anthropic true))
    (dotimes [_ 10]
      (health/record-backend-call! :anthropic false))

    ;; OpenAI: 70% success
    (dotimes [_ 70]
      (health/record-backend-call! :openai true))
    (dotimes [_ 30]
      (health/record-backend-call! :openai false))

    ;; Codex: 95% success
    (dotimes [_ 95]
      (health/record-backend-call! :codex true))
    (dotimes [_ 5]
      (health/record-backend-call! :codex false))

    (is (= 0.9 (health/get-backend-success-rate :anthropic)))
    (is (= 0.7 (health/get-backend-success-rate :openai)))
    (is (= 0.95 (health/get-backend-success-rate :codex)))))

(deftest test-last-failure-timestamp
  (testing "Last failure timestamp is recorded"
    (health/record-backend-call! :anthropic true)
    (health/record-backend-call! :anthropic false)

    (let [health-data (health/load-health)
          backend-stats (get-in health-data [:backends :anthropic])]
      (is (some? (:last-failure backend-stats)))
      (is (string? (:last-failure backend-stats))))))
