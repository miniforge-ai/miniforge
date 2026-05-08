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

(ns ai.miniforge.workflow.anomaly.try-load-chain-test
  "Coverage for `chain-loader/try-load-chain` (anomaly-returning) and
   the `chain-loader/load-chain` boundary that escalates a not-found
   anomaly to slingshot under `:anomalies/not-found`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.chain-loader :as chain-loader])
  (:import (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)

(deftest try-load-chain-returns-result-on-success
  (testing "existing chain resource yields a non-anomaly result map"
    (let [result (chain-loader/try-load-chain :spec-to-pr "1.0.0")]
      (is (not (anomaly/anomaly? result)))
      (is (some? (:chain result)))
      (is (= :resource (:source result))))))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)

(deftest try-load-chain-returns-anomaly-on-miss
  (testing "missing chain resource yields a :not-found anomaly with looked-for paths"
    (let [result (chain-loader/try-load-chain :nonexistent-chain "9.9.9")]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (let [data (:anomaly/data result)]
        (is (= :nonexistent-chain (:chain-id data)))
        (is (= "9.9.9" (:version data)))
        (is (seq (:looked-for data)))))))

;------------------------------------------------------------------------------ Boundary escalation through load-chain

(deftest load-chain-throws-on-miss
  (testing "load-chain escalates the not-found anomaly to slingshot"
    (try
      (chain-loader/load-chain :nonexistent-chain "9.9.9")
      (is false "should have thrown")
      (catch ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :anomalies/not-found (:anomaly/category data)))
          (is (= :nonexistent-chain (:chain-id data))))))))
