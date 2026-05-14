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

(ns ai.miniforge.connector.validation.validate-auth-or-throw-test
  "Coverage for `connector/validate-auth-or-throw!` — the localized
   boundary helper used by github/gitlab/jira connectors. Post-cleanup,
   the throw routes through `response/throw-anomaly!` carrying
   `:anomalies/incorrect`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector.interface :as connector])
  (:import (clojure.lang ExceptionInfo)))

(def ^:private valid-auth
  {:auth/method :api-key
   :auth/credential-id "cred-abc"})

(def ^:private invalid-auth
  {:auth/credential-id "cred-abc"})

(defn- fake-translator
  "Stand-in for a connector's `messages/t` — returns a deterministic
   localized message that exercises the `{errors}` interpolation."
  [_key {:keys [errors]}]
  (str "auth invalid: " (count errors) " errors"))

(deftest validate-auth-or-throw-returns-nil-on-success
  (testing "no-op when auth is valid"
    (is (nil? (connector/validate-auth-or-throw! valid-auth
                                                 fake-translator
                                                 :fake/auth-invalid)))))

(deftest validate-auth-or-throw-returns-nil-when-auth-absent
  (testing "no-op when auth is absent"
    (is (nil? (connector/validate-auth-or-throw! nil
                                                 fake-translator
                                                 :fake/auth-invalid)))))

(deftest validate-auth-or-throw-raises-localized-message
  (testing "invalid auth raises ExceptionInfo with translator-emitted message"
    (is (thrown-with-msg? ExceptionInfo
                          #"auth invalid: \d+ errors"
                          (connector/validate-auth-or-throw! invalid-auth
                                                             fake-translator
                                                             :fake/auth-invalid)))))

(deftest validate-auth-or-throw-ex-data-carries-anomaly-category
  (testing "thrown ex-data carries :anomalies/incorrect + :errors"
    (try
      (connector/validate-auth-or-throw! invalid-auth
                                         fake-translator
                                         :fake/auth-invalid)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= :anomalies/incorrect (:anomaly/category (ex-data e))))
        (is (seq (:errors (ex-data e))))))))
