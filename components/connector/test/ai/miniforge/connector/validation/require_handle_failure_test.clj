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

(ns ai.miniforge.connector.validation.require-handle-failure-test
  "Failure-path coverage for `connector/require-handle`: missing handles
   produce a canonical `:not-found` anomaly."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.connector.interface :as connector]))

(deftest require-handle-returns-anomaly-on-miss
  (testing "unknown handle returns an anomaly map"
    (let [store  (connector/create-handle-registry)
          result (connector/require-handle store "missing")]
      (is (anomaly/anomaly? result)))))

(deftest require-handle-uses-not-found-type
  (testing "anomaly type is :not-found — handle did not exist"
    (let [store  (connector/create-handle-registry)
          result (connector/require-handle store "missing")]
      (is (= :not-found (:anomaly/type result))))))

(deftest require-handle-anomaly-data-includes-handle
  (testing "anomaly data carries the missing handle for diagnostics"
    (let [store  (connector/create-handle-registry)
          result (connector/require-handle store "missing")]
      (is (= "missing" (get-in result [:anomaly/data :handle]))))))

(deftest require-handle-anomaly-data-includes-connector-tag
  (testing "connector keyword from opts surfaces in :anomaly/data"
    (let [store  (connector/create-handle-registry)
          result (connector/require-handle store "missing" {:connector :jira})]
      (is (= :jira (get-in result [:anomaly/data :connector]))))))

(deftest require-handle-uses-custom-message
  (testing "caller-supplied message is preserved on the anomaly"
    (let [store  (connector/create-handle-registry)
          result (connector/require-handle store
                                            "missing"
                                            {:message "Jira handle not found"})]
      (is (= "Jira handle not found" (:anomaly/message result))))))
