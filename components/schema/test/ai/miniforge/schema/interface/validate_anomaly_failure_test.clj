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

(ns ai.miniforge.schema.interface.validate-anomaly-failure-test
  "Failure-path coverage for `schema/validate-anomaly`: invalid values
   become canonical anomalies carrying useful diagnostic data."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.schema.interface :as schema]))

(def invalid-agent
  {:agent/id "not-a-uuid"
   :agent/role :invalid-role})

(deftest validate-anomaly-returns-anomaly-on-failure
  (testing "invalid value returns an anomaly map, not the value"
    (let [result (schema/validate-anomaly schema/Agent invalid-agent)]
      (is (anomaly/anomaly? result)))))

(deftest validate-anomaly-uses-invalid-input-type
  (testing "anomaly type is :invalid-input — it's a validation failure"
    (let [result (schema/validate-anomaly schema/Agent invalid-agent)]
      (is (= :invalid-input (:anomaly/type result))))))

(deftest validate-anomaly-message-is-human-readable
  (testing "anomaly carries a human-readable message"
    (let [result (schema/validate-anomaly schema/Agent invalid-agent)]
      (is (string? (:anomaly/message result)))
      (is (seq (:anomaly/message result))))))

(deftest validate-anomaly-data-carries-errors
  (testing "anomaly data includes humanized malli errors"
    (let [result (schema/validate-anomaly schema/Agent invalid-agent)
          errors (get-in result [:anomaly/data :errors])]
      (is (some? errors))
      (is (map? errors)))))

(deftest validate-anomaly-data-carries-offending-value
  (testing "anomaly data includes the offending value for diagnostics"
    (let [result (schema/validate-anomaly schema/Agent invalid-agent)]
      (is (= invalid-agent (get-in result [:anomaly/data :value]))))))
