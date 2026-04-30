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

(ns ai.miniforge.connector.validation.validate-auth-throwing-compat-test
  "Backward-compat coverage for the deprecated throwing
   `connector/validate-auth!`. Per-connector helpers depend on the
   throwing shape today; this test pins the contract while migration
   to the anomaly-returning variant proceeds incrementally."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector.interface :as connector]))

(def valid-auth
  {:auth/method :api-key
   :auth/credential-id "cred-abc"})

(def invalid-auth
  {:auth/credential-id "cred-abc"})

(deftest validate-auth-bang-returns-nil-on-success
  (testing "deprecated thrower is a no-op for valid credentials"
    (is (nil? (connector/validate-auth! valid-auth)))))

(deftest validate-auth-bang-returns-nil-when-auth-absent
  (testing "deprecated thrower is a no-op when no credentials supplied"
    (is (nil? (connector/validate-auth! nil)))))

(deftest validate-auth-bang-throws-on-invalid
  (testing "deprecated thrower throws ExceptionInfo on bad credentials"
    (is (thrown? clojure.lang.ExceptionInfo
                 (connector/validate-auth! invalid-auth)))))

(deftest validate-auth-bang-ex-data-carries-errors
  (testing "thrown ex-info data carries underlying validation errors"
    (try
      (connector/validate-auth! invalid-auth)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (seq (:errors (ex-data e))))))))
