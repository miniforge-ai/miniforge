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

(ns ai.miniforge.dag-primitives.unwrap-anomaly-failure-test
  "Failure-path coverage for `dag-primitives/unwrap-anomaly`: err results
   become canonical anomalies that preserve the original error context."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.dag-primitives.interface :as dp]))

(deftest unwrap-anomaly-returns-anomaly-on-err
  (testing "err result becomes a canonical anomaly map"
    (let [result (dp/unwrap-anomaly (dp/err :cycle-detected "graph has a cycle"))]
      (is (anomaly/anomaly? result)))))

(deftest unwrap-anomaly-uses-fault-type
  (testing "anomaly type is :fault — err results signal an internal failure"
    (let [result (dp/unwrap-anomaly (dp/err :cycle-detected "graph has a cycle"))]
      (is (= :fault (:anomaly/type result))))))

(deftest unwrap-anomaly-preserves-message
  (testing "err message becomes the anomaly message"
    (let [result (dp/unwrap-anomaly (dp/err :cycle-detected "graph has a cycle"))]
      (is (= "graph has a cycle" (:anomaly/message result))))))

(deftest unwrap-anomaly-preserves-error-code
  (testing "err code is exposed in :anomaly/data :code"
    (let [result (dp/unwrap-anomaly (dp/err :cycle-detected "graph has a cycle"))]
      (is (= :cycle-detected (get-in result [:anomaly/data :code]))))))

(deftest unwrap-anomaly-preserves-error-data
  (testing "err data is exposed in :anomaly/data :error-data"
    (let [result (dp/unwrap-anomaly (dp/err :cycle-detected
                                            "graph has a cycle"
                                            {:nodes #{:a :b}}))]
      (is (= {:nodes #{:a :b}}
             (get-in result [:anomaly/data :error-data]))))))

(deftest unwrap-anomaly-handles-missing-message
  (testing "err with no message still produces a valid anomaly"
    (let [bare-err {:ok? false :error {:code :unknown}}
          result   (dp/unwrap-anomaly bare-err)]
      (is (anomaly/anomaly? result))
      (is (string? (:anomaly/message result)))
      (is (seq (:anomaly/message result))))))
