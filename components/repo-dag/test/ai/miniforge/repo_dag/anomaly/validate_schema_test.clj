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

(ns ai.miniforge.repo-dag.anomaly.validate-schema-test
  "Coverage for `core/validate-schema-anomaly` and its deprecated
   throwing sibling `core/validate-schema`. Schema rejection is an
   :invalid-input anomaly; valid values pass through unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.repo-dag.core :as core]
            [ai.miniforge.repo-dag.interface :as dag]))

(def valid-repo
  {:repo/url "https://github.com/acme/tf"
   :repo/name "tf"
   :repo/type :terraform-module
   :repo/layer :foundations
   :repo/default-branch "main"})

(def invalid-repo
  {:repo/name "missing-required-fields"})

;------------------------------------------------------------------------------ Happy path

(deftest validate-schema-anomaly-returns-value-on-success
  (testing "valid value passes through unchanged"
    (is (= valid-repo (core/validate-schema-anomaly dag/RepoNode valid-repo))))
  (testing "result is identical to the input (no copy)"
    (is (identical? valid-repo
                    (core/validate-schema-anomaly dag/RepoNode valid-repo)))))

;------------------------------------------------------------------------------ Failure path

(deftest validate-schema-anomaly-returns-anomaly-on-failure
  (testing "invalid value yields a canonical anomaly"
    (let [result (core/validate-schema-anomaly dag/RepoNode invalid-repo)]
      (is (anomaly/anomaly? result)))))

(deftest validate-schema-anomaly-uses-invalid-input-type
  (testing "schema validation failure is :invalid-input"
    (let [result (core/validate-schema-anomaly dag/RepoNode invalid-repo)]
      (is (= :invalid-input (:anomaly/type result))))))

(deftest validate-schema-anomaly-data-carries-context
  (testing "anomaly carries schema, value, and humanized errors"
    (let [result (core/validate-schema-anomaly dag/RepoNode invalid-repo)
          data   (:anomaly/data result)]
      (is (= dag/RepoNode (:schema data)))
      (is (= invalid-repo (:value data)))
      (is (some? (:errors data)))
      (is (map? (:errors data))))))

;------------------------------------------------------------------------------ Throwing-variant compat

(deftest validate-schema-still-returns-on-success
  (testing "deprecated throwing variant returns the value unchanged"
    (is (= valid-repo (core/validate-schema dag/RepoNode valid-repo)))))

(deftest validate-schema-still-throws-ex-info
  (testing "deprecated throwing variant still throws on bad input"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Schema validation failed"
          (core/validate-schema dag/RepoNode invalid-repo)))))

(deftest validate-schema-thrown-ex-data-carries-errors
  (testing "ex-data preserves :schema, :value, and :errors for legacy callers"
    (try
      (core/validate-schema dag/RepoNode invalid-repo)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= dag/RepoNode (:schema data)))
          (is (= invalid-repo (:value data)))
          (is (map? (:errors data))))))))
