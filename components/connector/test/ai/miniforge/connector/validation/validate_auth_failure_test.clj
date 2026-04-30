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

(ns ai.miniforge.connector.validation.validate-auth-failure-test
  "Failure-path coverage for `connector/validate-auth`: malformed
   credentials become a canonical `:invalid-input` anomaly."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.connector.interface :as connector]))

(def invalid-auth
  ;; Missing :auth/method — connector-auth flags this as a method-required error.
  {:auth/credential-id "cred-abc"})

(deftest validate-auth-returns-anomaly-on-bad-credentials
  (testing "malformed credential ref returns an anomaly map"
    (is (anomaly/anomaly? (connector/validate-auth invalid-auth)))))

(deftest validate-auth-uses-invalid-input-type
  (testing "anomaly type is :invalid-input — credentials failed validation"
    (let [result (connector/validate-auth invalid-auth)]
      (is (= :invalid-input (:anomaly/type result))))))

(deftest validate-auth-anomaly-data-includes-errors
  (testing "anomaly data carries the underlying validation errors"
    (let [result (connector/validate-auth invalid-auth)
          errors (get-in result [:anomaly/data :errors])]
      (is (seq errors)))))

(deftest validate-auth-anomaly-data-includes-connector-tag
  (testing "connector keyword from opts surfaces in :anomaly/data"
    (let [result (connector/validate-auth invalid-auth {:connector :gitlab})]
      (is (= :gitlab (get-in result [:anomaly/data :connector]))))))

(deftest validate-auth-uses-custom-message
  (testing "caller-supplied message is preserved on the anomaly"
    (let [result (connector/validate-auth invalid-auth
                                          {:message "Gitlab credentials invalid"})]
      (is (= "Gitlab credentials invalid" (:anomaly/message result))))))
