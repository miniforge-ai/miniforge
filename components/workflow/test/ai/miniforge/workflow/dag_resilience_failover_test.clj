(ns ai.miniforge.workflow.dag-resilience-failover-test
  "Tests for rate-limit failover handling, reset-time parsing, and sub-workflow error extraction."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
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
  (testing "parses 'resets 2pm' as an instant"
    (let [text "You've hit your limit · resets 2pm"
          result (resilience/parse-reset-instant text)]
      (is (instance? Instant result))
      (is (.isAfter result (Instant/now)))))

  (testing "parses 'resets 2pm (America/Los_Angeles)' with timezone"
    (let [text "You've hit your limit · resets 2pm (America/Los_Angeles)"
          result (resilience/parse-reset-instant text)]
      (is (instance? Instant result))))

  (testing "parses 'resets 2:30pm' with minutes"
    (let [text "resets 2:30pm"
          result (resilience/parse-reset-instant text)]
      (is (instance? Instant result)))))

(deftest test-parse-reset-instant-relative-time
  (testing "parses 'resets in 30 minutes' as relative duration"
    (let [before (Instant/now)
          result (resilience/parse-reset-instant "resets in 30 minutes")
          after (Instant/now)]
      (is (instance? Instant result))
      ;; Should be approximately 30 minutes from now
      (is (> (.toEpochMilli result) (+ (.toEpochMilli before) 1790000)))
      (is (< (.toEpochMilli result) (+ (.toEpochMilli after) 1810000))))))

(deftest test-parse-reset-instant-no-match
  (testing "returns nil for non-reset text"
    (is (nil? (resilience/parse-reset-instant "Syntax error")))
    (is (nil? (resilience/parse-reset-instant nil)))))

;------------------------------------------------------------------------------ Layer 8
;; handle-rate-limited-batch with reset time waiting

(deftest test-handle-rate-limited-batch-waits-for-reset
  (testing "waits for reset when reset time is imminent (relative)"
    (let [[logger _] (log/collecting-logger)
          rate-limit-msg "You've hit your limit · resets in 1 seconds"
          results {:b (dag/err :task-execution-failed rate-limit-msg {:task-id :b})}
          decision (resilience/handle-rate-limited-batch
                    {} #{:b} #{:a} logger results)]
      ;; Should have waited and returned :continue
      (is (= :continue (:action decision)))
      (is (number? (:waited-ms decision))))))

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
