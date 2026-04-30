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

(ns ai.miniforge.connector.validation.validate-auth-happy-path-test
  "Happy-path coverage for `connector/validate-auth`: nil credentials and
   valid credentials both return nil (no anomaly)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.connector.interface :as connector]))

(def valid-auth
  {:auth/method :api-key
   :auth/credential-id "cred-abc"})

(deftest validate-auth-returns-nil-when-auth-absent
  (testing "no credentials supplied -> nothing to validate, returns nil"
    (is (nil? (connector/validate-auth nil)))))

(deftest validate-auth-returns-nil-on-valid-credentials
  (testing "valid credential ref returns nil (success)"
    (is (nil? (connector/validate-auth valid-auth)))))

(deftest validate-auth-success-not-an-anomaly
  (testing "valid credentials never produce an anomaly"
    (is (not (anomaly/anomaly? (connector/validate-auth valid-auth))))
    (is (not (anomaly/anomaly? (connector/validate-auth nil))))))
