;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.workflow.runner-iteration-test
  "Tests for iteration helpers extracted from runner.clj."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.runner :as runner]))

;; Access private fns
(def ^:private rate-limited? #'runner/rate-limited?)
(defn- backoff-ms [n] (#'runner/backoff-ms n))
(def ^:private make-execution-error #'runner/make-execution-error)

;; ============================================================================
;; rate-limited?
;; ============================================================================

(deftest rate-limited-detects-429-test
  (testing "detects HTTP 429 in error message"
    (is (true? (rate-limited? "HTTP 429 Too Many Requests")))))

(deftest rate-limited-detects-rate-limit-test
  (testing "detects 'rate limit' phrase"
    (is (true? (rate-limited? "You've hit your rate limit")))))

(deftest rate-limited-detects-quota-exceeded-test
  (testing "detects quota exceeded"
    (is (true? (rate-limited? "API quota exceeded for this model")))))

(deftest rate-limited-false-for-normal-errors-test
  (testing "returns false for non-rate-limit errors"
    (is (false? (rate-limited? "Syntax error on line 10")))
    (is (false? (rate-limited? "Connection refused")))
    (is (false? (rate-limited? "")))))

(deftest rate-limited-handles-nil-test
  (testing "handles nil input"
    (is (false? (rate-limited? nil)))))

;; ============================================================================
;; backoff-ms
;; ============================================================================

(deftest backoff-ms-first-retry-test
  (testing "first retry uses base backoff"
    (is (= 1000 (backoff-ms 1)))))

(deftest backoff-ms-doubles-test
  (testing "second retry doubles"
    (is (= 2000 (backoff-ms 2)))))

(deftest backoff-ms-capped-test
  (testing "large retry count is capped at max"
    (is (<= (backoff-ms 100) 30000))))

;; ============================================================================
;; make-execution-error
;; ============================================================================

(deftest make-execution-error-basic-test
  (testing "produces map with :type and :message"
    (let [err (make-execution-error :test-error "something broke")]
      (is (= :test-error (:type err)))
      (is (= "something broke" (:message err))))))

(deftest make-execution-error-with-extra-test
  (testing "merges extra data"
    (let [err (make-execution-error :test-error "broke" {:data {:code 42}})]
      (is (= :test-error (:type err)))
      (is (= 42 (get-in err [:data :code]))))))
