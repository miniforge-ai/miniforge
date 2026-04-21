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

(ns ai.miniforge.phase-deployment.gates-test
  (:require [ai.miniforge.gate.registry :as registry]
            [ai.miniforge.phase-deployment.gates :as sut]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Deployment gate tests

(deftest check-provision-validated-test
  (testing "returns a failed gate result when outputs are missing"
    (let [result (sut/check-provision-validated {} {})]
      (is (false? (:passed? result)))
      (is (= :no-outputs (get-in result [:errors 0 :type])))))

  (testing "returns a failed gate result when expected outputs are missing"
    (let [result (sut/check-provision-validated {:outputs {:db-host "10.0.0.1"}}
                                                {:expected-outputs #{:db-host :db-port}})]
      (is (false? (:passed? result)))
      (is (= :missing-outputs (get-in result [:errors 0 :type]))))))

(deftest check-health-endpoint-test
  (testing "falls back to the localized default message for failed smoke tests"
    (let [result (sut/check-health-endpoint {:smoke-results [{:passed? false :command "curl"}]} {})]
      (is (false? (:passed? result)))
      (is (= "Command failed" (get-in result [:errors 0 :message]))))))

(deftest gate-registration-test
  (testing "gate descriptions are loaded from EDN-backed configuration"
    (is (= "Validates health endpoints return HTTP 2xx"
           (:description (registry/get-gate :health-check))))))
