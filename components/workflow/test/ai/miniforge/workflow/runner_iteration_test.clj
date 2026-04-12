;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.workflow.runner-iteration-test
  "Tests for iteration helpers extracted from runner.clj.
   Includes slingshot try+/throw+ bb-compatibility tests."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.runner :as runner]
   [slingshot.slingshot :refer [try+ throw+]]))

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

;; ============================================================================
;; slingshot try+/throw+ — bb compatibility tests
;; ============================================================================

(deftest slingshot-throw-plus-map-test
  (testing "throw+ with map is caught by key-value selector"
    (let [result (try+
                   (throw+ {:type :rate-limited :status 429 :message "slow down"})
                   (catch [:type :rate-limited] {:keys [status message]}
                     {:caught true :status status :message message}))]
      (is (true? (:caught result)))
      (is (= 429 (:status result)))
      (is (= "slow down" (:message result))))))

(deftest slingshot-catches-ex-info-by-key-test
  (testing "try+ catches existing ex-info throws by key-value selector"
    (let [result (try+
                   (throw (ex-info "gate failed" {:type :gate-failed :gate :lint}))
                   (catch [:type :gate-failed] {:keys [gate]}
                     {:caught true :gate gate}))]
      (is (true? (:caught result)))
      (is (= :lint (:gate result))))))

(deftest slingshot-fallthrough-to-object-test
  (testing "try+ falls through to Object catch for non-matching ex-info throws"
    (let [result (try+
                   (throw (ex-info "other error" {:type :unknown}))
                   (catch [:type :rate-limited] _
                     {:caught :rate-limited})
                   (catch Object obj
                     {:caught :fallback :type (:type obj)}))]
      (is (= :fallback (:caught result)))
      (is (= :unknown (:type result))))))

(deftest slingshot-dashboard-stop-test
  (testing "check-stopped! throw+ is caught by :type selector"
    (let [result (try+
                   (throw+ {:type :dashboard-stop :reason :dashboard-stop :message "stopped"})
                   (catch [:type :dashboard-stop] {:keys [message]}
                     {:caught true :message message}))]
      (is (true? (:caught result)))
      (is (= "stopped" (:message result))))))
