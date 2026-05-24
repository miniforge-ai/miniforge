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

(ns ai.miniforge.progress-detector.anomaly.progress-detector-anomaly-test
  "Coverage for `event-envelope/validate-observation!`,
   `config/merge-config`, and `tool-profile/register!` boundary
   escalation via `response/throw-anomaly!`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.progress-detector.config :as config]
            [ai.miniforge.progress-detector.event-envelope :as envelope]
            [ai.miniforge.progress-detector.tool-profile :as tool-profile])
  (:import (clojure.lang ExceptionInfo)))

(deftest validate-observation-schema-failure-throws-anomaly
  (testing "private validate-observation! raises :anomalies/incorrect on bad obs"
    ;; validate-observation! is private; reach via the var. Boundary
    ;; escalation is what we're pinning, not the private fn's name.
    (let [thrown (try
                   (@#'envelope/validate-observation!
                    {}
                    {:not-a-real :observation})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      ;; Message text is i18n-keyed (:envelope/validation-failed); assert
      ;; non-blank rather than a fixed substring so the test doesn't pin
      ;; the en-US translation. Other tests in this file assert on the
      ;; pre-existing English error strings, which are not message-catalog
      ;; entries.
      (is (seq (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))

(deftest merge-config-empty-layers-throws-anomaly
  (testing "empty layers raises :anomalies/incorrect"
    (let [thrown (try (config/merge-config []) nil (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"requires at least one layer" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))

(deftest register-missing-tool-id-throws-anomaly
  (testing "profile missing :tool/id raises :anomalies/incorrect"
    (let [registry (atom {})
          thrown (try (tool-profile/register! registry {})
                      nil
                      (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"must include :tool/id" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))

(deftest register-invalid-schema-throws-anomaly
  (testing "profile failing schema validation raises :anomalies/incorrect"
    (let [registry (atom {})
          thrown (try (tool-profile/register! registry {:tool/id :bogus
                                                        :tool/garbage 42})
                      nil
                      (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"ToolProfile schema validation" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))
