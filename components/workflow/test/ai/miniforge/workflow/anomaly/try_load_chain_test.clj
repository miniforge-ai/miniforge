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
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.chain-loader :as chain-loader]
            [ai.miniforge.workflow.chain-test-support :as support]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)
(def ^{:stratum 0} ^:private spec-to-pr-versioned-path
  "chains/spec-to-pr-v1.0.0.edn")

(def ^{:stratum 0} ^:private stub-resource-url
  (java.net.URL. "file:/tmp/miniforge-try-load-chain-test-resource"))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)
(deftest ^{:stratum 0} try-load-chain-returns-anomaly-on-miss
  (testing "missing chain resource yields a :not-found anomaly with looked-for paths"
    (let [result (chain-loader/try-load-chain :nonexistent-chain "9.9.9")]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (let [data (:anomaly/data result)]
        (is (= :nonexistent-chain (:chain-id data)))
        (is (= "9.9.9" (:version data)))
        (is (seq (:looked-for data)))))))

;------------------------------------------------------------------------------ Boundary escalation through load-chain
(deftest ^{:stratum 0} load-chain-throws-on-miss
  (testing "load-chain escalates the not-found anomaly to slingshot"
    (try+
      (chain-loader/load-chain :nonexistent-chain "9.9.9")
      (is false "should have thrown")
      (catch [:anomaly/category :anomalies/not-found] data
        (is (= :anomalies/not-found (:anomaly/category data)))
        (is (= :nonexistent-chain (:chain-id data)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} fixture-resource [path]
  (when (= spec-to-pr-versioned-path path) stub-resource-url))

(defn- ^{:stratum 1} fixture-definition [path]
  (when (= spec-to-pr-versioned-path path) (support/definition :spec-to-pr "1.0.0")))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} try-load-chain-returns-result-on-success
  (testing "existing chain resource yields a non-anomaly result map"
    (with-redefs [io/resource fixture-resource
                  chain-loader/load-chain-resource fixture-definition]
      (let [result (chain-loader/try-load-chain :spec-to-pr "1.0.0")]
        (is (not (anomaly/anomaly? result)))
        (is (some? (:chain result)))
        (is (= :resource (:source result)))))))

(comment
  (fixture-definition spec-to-pr-versioned-path))
