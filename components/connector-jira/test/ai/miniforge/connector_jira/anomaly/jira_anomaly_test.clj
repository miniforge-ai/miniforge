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

(ns ai.miniforge.connector-jira.anomaly.jira-anomaly-test
  "Coverage for `impl/do-connect`, `impl/require-resource!`, and
   `schema/validate!` boundary escalation via `response/throw-anomaly!`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [ai.miniforge.connector-jira.impl :as impl]
            [ai.miniforge.connector-jira.resources :as resources]
            [ai.miniforge.connector-jira.schema :as schema])
  (:import (clojure.lang ExceptionInfo)))

(deftest do-connect-missing-site-throws-anomaly
  (testing "config without :jira/site raises :anomalies/incorrect"
    (try
      (impl/do-connect {} nil)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))))))

(deftest require-resource-unknown-throws-anomaly
  (testing "looking up an unknown resource raises :anomalies/not-found"
    (try
      (@#'impl/require-resource! "totally-bogus-resource")
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/not-found (:anomaly/category (ex-data e))))))))

(deftest schema-validate-bang-failure-throws-anomaly
  (testing "schema validation failure raises :anomalies/incorrect"
    (try
      (schema/validate! schema/JiraConfig {:jira/site 42})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))
        (is (= :invalid-config (:config/error (ex-data e))))
        (is (some? (:errors (ex-data e))))))))

(deftest load-resources-missing-edn-throws-anomaly
  (testing "missing resource EDN raises :anomalies/not-found"
    (with-redefs [io/resource (constantly nil)]
      (try
        (@#'resources/load-resources)
        (is false "should have thrown")
        (catch ExceptionInfo e
          (is (= :anomalies/not-found (:anomaly/category (ex-data e))))
          (is (= "config/connector-jira/resources.edn" (:path (ex-data e)))))))))
