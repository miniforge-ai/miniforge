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

(ns ai.miniforge.connector-retry.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-retry.interface :as retry]))

(deftest error-classification-test
  (testing "N2 §6.1 error categories"
    (is (true? (retry/retryable? :transient)))
    (is (true? (retry/retryable? :rate-limited)))
    (is (false? (retry/retryable? :permanent)))))

(deftest fixed-backoff-test
  (testing "N2 §6.3.1 fixed backoff"
    (let [policy {:retry/strategy :fixed
                  :retry/max-attempts 3
                  :retry/initial-delay-ms 1000}]
      (is (= 1000 (retry/compute-delay policy 0)))
      (is (= 1000 (retry/compute-delay policy 1)))
      (is (= 1000 (retry/compute-delay policy 2))))))

(deftest exponential-backoff-test
  (testing "N2 §6.3.2 exponential backoff"
    (let [policy {:retry/strategy :exponential
                  :retry/max-attempts 5
                  :retry/initial-delay-ms 1000
                  :retry/max-delay-ms 60000
                  :retry/backoff-multiplier 2.0}]
      (is (= 1000 (retry/compute-delay policy 0)))
      (is (= 2000 (retry/compute-delay policy 1)))
      (is (= 4000 (retry/compute-delay policy 2)))
      (is (= 8000 (retry/compute-delay policy 3)))
      (is (= 16000 (retry/compute-delay policy 4))))))

(deftest exponential-backoff-cap-test
  (testing "Delay capped at max-delay-ms"
    (let [policy {:retry/strategy :exponential
                  :retry/max-attempts 10
                  :retry/initial-delay-ms 1000
                  :retry/max-delay-ms 5000
                  :retry/backoff-multiplier 2.0}]
      (is (= 4000 (retry/compute-delay policy 2)))
      (is (= 5000 (retry/compute-delay policy 3)))
      (is (= 5000 (retry/compute-delay policy 9))))))

(deftest jittered-backoff-test
  (testing "N2 §6.3.3 jittered exponential - within expected range"
    (let [policy {:retry/strategy :jittered-exponential
                  :retry/max-attempts 3
                  :retry/initial-delay-ms 1000
                  :retry/max-delay-ms 60000
                  :retry/backoff-multiplier 2.0}
          delays (repeatedly 100 #(retry/compute-delay policy 1))
          base 2000]
      ;; jitter = base * (0.5 + random(0,1)) so range is [base*0.5, base*1.5)
      (is (every? #(and (>= % (* base 0.5)) (< % (* base 1.5))) delays)))))

(deftest should-retry-test
  (testing "ERR-01: Transient error retries"
    (let [policy {:retry/strategy :exponential
                  :retry/max-attempts 5
                  :retry/initial-delay-ms 1000}]
      (is (true? (retry/should-retry? policy 0 :transient)))
      (is (true? (retry/should-retry? policy 3 :transient)))
      (is (false? (retry/should-retry? policy 4 :transient)))))

  (testing "ERR-02: Permanent error never retries"
    (let [policy {:retry/strategy :fixed
                  :retry/max-attempts 5
                  :retry/initial-delay-ms 1000}]
      (is (false? (retry/should-retry? policy 0 :permanent))))))

(deftest retry-sequence-test
  (testing "Full sequence of delays"
    (let [policy {:retry/strategy :exponential
                  :retry/max-attempts 4
                  :retry/initial-delay-ms 500
                  :retry/backoff-multiplier 2.0}]
      (is (= [500 1000 2000 4000] (retry/retry-sequence policy))))))

(deftest circuit-breaker-test
  (testing "ERR-03: Circuit breaker opens after threshold"
    (let [cb (retry/create-circuit-breaker {:breaker/failure-threshold 3
                                            :breaker/reset-timeout-ms 5000})
          cb1 (retry/record-failure cb)
          cb2 (retry/record-failure cb1)
          cb3 (retry/record-failure cb2)]
      (is (= :closed (retry/breaker-state cb1)))
      (is (= :closed (retry/breaker-state cb2)))
      (is (= :open (retry/breaker-state cb3)))
      (is (false? (retry/allow-request? cb3)))))

  (testing "ERR-04: Circuit breaker half-open recovery"
    (let [now (System/currentTimeMillis)
          cb {:breaker/state :open
              :breaker/failure-count 5
              :breaker/failure-threshold 5
              :breaker/reset-timeout-ms 1000
              :breaker/last-failure-at (- now 2000)
              :breaker/last-success-at nil}]
      ;; After timeout, should be half-open
      (is (= :half-open (retry/breaker-state cb now)))
      (is (true? (retry/allow-request? cb now)))
      ;; Success resets to closed
      (let [recovered (retry/record-success cb)]
        (is (= :closed (retry/breaker-state recovered)))))))
