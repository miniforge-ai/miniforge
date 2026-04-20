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

(ns ai.miniforge.pr-lifecycle.ci-monitor-property-test
  "Property-based tests for CI monitor status computation.

   Verifies invariants of compute-ci-status using generative testing:
   - Total always equals input count
   - Status is always a recognized keyword
   - Failure takes precedence over pending
   - Pending takes precedence over success
   - Empty input yields :unknown
   - All-success input yields :success"
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.pr-lifecycle.ci-monitor :as ci]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Helpers

(def valid-states
  "Realistic GitHub check states."
  ["COMPLETED" "QUEUED" "WAITING" "IN_PROGRESS" nil])

(def valid-conclusions
  "Realistic GitHub check conclusions."
  ["SUCCESS" "FAILURE" "NEUTRAL" "CANCELLED" "SKIPPED"
   "ACTION_REQUIRED" "TIMED_OUT" nil])

(defn random-check
  "Generate a random CI check map."
  [i]
  {:name (str "check-" i)
   :state (rand-nth valid-states)
   :conclusion (rand-nth valid-conclusions)})

(defn random-checks
  "Generate n random CI check maps."
  [n]
  (mapv random-check (range n)))

;------------------------------------------------------------------------------ Property: Total invariant

(deftest compute-ci-status-total-invariant-test
  (testing "Property: :total always equals input count for random checks"
    (dotimes [_ 100]
      (let [n (rand-int 20)
            checks (random-checks n)
            result (ci/compute-ci-status checks)]
        (is (= n (:total result))
            (str "Total should be " n " for " n " checks"))))))

;------------------------------------------------------------------------------ Property: Status in ci-statuses

(deftest compute-ci-status-recognized-status-test
  (testing "Property: status is always a member of ci-statuses for random checks"
    (dotimes [_ 100]
      (let [checks (random-checks (rand-int 20))
            result (ci/compute-ci-status checks)]
        (is (contains? ci/ci-statuses (:status result))
            (str "Status " (:status result) " should be in ci-statuses"))))))

;------------------------------------------------------------------------------ Property: Result map shape

(deftest compute-ci-status-result-shape-test
  (testing "Property: result always contains required keys"
    (dotimes [_ 50]
      (let [checks (random-checks (rand-int 15))
            result (ci/compute-ci-status checks)]
        (is (contains? result :status))
        (is (contains? result :passed))
        (is (contains? result :failed))
        (is (contains? result :pending))
        (is (contains? result :neutral))
        (is (contains? result :total))
        (is (vector? (:passed result)))
        (is (vector? (:failed result)))
        (is (vector? (:pending result)))
        (is (vector? (:neutral result)))
        (is (integer? (:total result)))))))

;------------------------------------------------------------------------------ Property: Failure precedence

(deftest compute-ci-status-failure-precedence-test
  (testing "Property: if any check has FAILURE conclusion with COMPLETED state, status is :failure"
    (dotimes [_ 50]
      (let [base-checks (random-checks (rand-int 10))
            fail-check {:name "forced-fail" :state "COMPLETED" :conclusion "FAILURE"}
            checks (conj base-checks fail-check)
            result (ci/compute-ci-status checks)]
        (is (= :failure (:status result))
            "Failure should always take precedence")))))

;------------------------------------------------------------------------------ Property: All-success yields :success

(deftest compute-ci-status-all-success-test
  (testing "Property: all COMPLETED/SUCCESS checks always yield :success"
    (doseq [n (range 1 20)]
      (let [checks (vec (repeat n {:name "test" :state "COMPLETED" :conclusion "SUCCESS"}))
            result (ci/compute-ci-status checks)]
        (is (response/success? result)
            (str n " SUCCESS checks should yield :success"))))))

;------------------------------------------------------------------------------ Property: Empty yields :unknown

(deftest compute-ci-status-empty-yields-unknown-test
  (testing "Property: empty checks always yield :unknown"
    ;; Run multiple times to ensure consistency
    (dotimes [_ 10]
      (is (= :unknown (:status (ci/compute-ci-status [])))))))

;------------------------------------------------------------------------------ Property: Pending blocks success

(deftest compute-ci-status-pending-blocks-success-test
  (testing "Property: QUEUED check prevents :success status (unless failure present)"
    (dotimes [_ 50]
      (let [success-checks (vec (for [i (range (inc (rand-int 5)))]
                                  {:name (str "pass-" i) :state "COMPLETED" :conclusion "SUCCESS"}))
            pending-check {:name "queued" :state "QUEUED" :conclusion nil}
            checks (conj success-checks pending-check)
            result (ci/compute-ci-status checks)]
        (is (= :pending (:status result))
            "QUEUED check should block :success status")))))

;------------------------------------------------------------------------------ Property: Grouped counts are non-negative

(deftest compute-ci-status-grouped-counts-non-negative-test
  (testing "Property: all grouped count vectors have non-negative length"
    (dotimes [_ 50]
      (let [checks (random-checks (rand-int 15))
            result (ci/compute-ci-status checks)]
        (is (>= (count (:passed result)) 0))
        (is (>= (count (:failed result)) 0))
        (is (>= (count (:pending result)) 0))
        (is (>= (count (:neutral result)) 0))))))

;------------------------------------------------------------------------------ Property: Deterministic

(deftest compute-ci-status-deterministic-test
  (testing "Property: same input always produces same output"
    (dotimes [_ 20]
      (let [checks (random-checks (inc (rand-int 10)))
            r1 (ci/compute-ci-status checks)
            r2 (ci/compute-ci-status checks)]
        (is (= (:status r1) (:status r2)))
        (is (= (:total r1) (:total r2)))
        (is (= (count (:passed r1)) (count (:passed r2))))
        (is (= (count (:failed r1)) (count (:failed r2))))
        (is (= (count (:pending r1)) (count (:pending r2))))
        (is (= (count (:neutral r1)) (count (:neutral r2))))))))
