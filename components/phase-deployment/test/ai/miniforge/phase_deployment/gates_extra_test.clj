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

(ns ai.miniforge.phase-deployment.gates-extra-test
  "Expanded coverage for phase-deployment gate checks. Existing
   gates_test.clj covers the smoke paths; this file fills out the matrix
   of pass / fail / artifact-shape variations."
  (:require [ai.miniforge.phase-deployment.gates :as sut]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn- pod
  ([name ready?] {:name name :ready? ready?}))

(defn- ok-pods-artifact
  [pod-count]
  {:pod-count pod-count
   :ready-count pod-count
   :pods (vec (for [i (range pod-count)] (pod (str "p" i) true)))})

;------------------------------------------------------------------------------ Layer 1
;; check-provision-validated — pass + nested-content variations

(deftest check-provision-validated-passes-with-outputs-test
  (testing "Outputs map present and no expected keys ⇒ passes"
    (let [r (sut/check-provision-validated {:outputs {:gke "https://x"}} {})]
      (is (true? (:passed? r)))
      (is (= 1 (:output-count r)))
      (is (= [:gke] (:output-keys r))))))

(deftest check-provision-validated-passes-with-expected-met-test
  (testing "Expected outputs all present ⇒ passes"
    (let [r (sut/check-provision-validated
              {:outputs {:gke-endpoint "x" :db-host "y"}}
              {:expected-outputs #{:gke-endpoint :db-host}})]
      (is (true? (:passed? r)))
      (is (= 2 (:output-count r))))))

(deftest check-provision-validated-reads-nested-content-test
  (testing "Outputs nested under :content map are recognized"
    (let [r (sut/check-provision-validated
              {:content {:outputs {:gke "x"}}} {})]
      (is (true? (:passed? r)))
      (is (= 1 (:output-count r))))))

(deftest check-provision-validated-reads-artifact-content-test
  (testing "Outputs nested under :artifact/content map are recognized"
    (let [r (sut/check-provision-validated
              {:artifact/content {:outputs {:gke "x"}}} {})]
      (is (true? (:passed? r))))))

(deftest check-provision-validated-fails-on-empty-outputs-test
  (testing "Empty outputs map ⇒ fails with :no-outputs error type"
    (let [r (sut/check-provision-validated {:outputs {}} {})]
      (is (false? (:passed? r)))
      (is (= :no-outputs (get-in r [:errors 0 :type]))))))

;------------------------------------------------------------------------------ Layer 1
;; check-deploy-healthy — pass / fail / nested

(deftest check-deploy-healthy-passes-with-all-ready-test
  (testing "All pods ready ⇒ passes; result reports counts"
    (let [r (sut/check-deploy-healthy (ok-pods-artifact 3) {})]
      (is (true? (:passed? r)))
      (is (= 3 (:pod-count r)))
      (is (= 3 (:ready-count r))))))

(deftest check-deploy-healthy-fails-on-zero-pods-test
  (testing "Zero pods deployed ⇒ :no-pods error"
    (let [r (sut/check-deploy-healthy {:pod-count 0 :ready-count 0 :pods []} {})]
      (is (false? (:passed? r)))
      (is (= :no-pods (get-in r [:errors 0 :type]))))))

(deftest check-deploy-healthy-fails-when-not-all-ready-test
  (testing "Some pods not ready ⇒ :pods-not-ready error with details"
    (let [r (sut/check-deploy-healthy
              {:pod-count 3 :ready-count 1
               :pods [(pod "p1" true) (pod "p2" false) (pod "p3" false)]}
              {})]
      (is (false? (:passed? r)))
      (is (= :pods-not-ready (get-in r [:errors 0 :type])))
      (is (= 2 (count (get-in r [:errors 0 :pod-details])))))))

(deftest check-deploy-healthy-reads-content-key-test
  (testing "Pod data nested under :content is recognized"
    (let [r (sut/check-deploy-healthy
              {:content {:pod-count 2 :ready-count 2
                         :pods [(pod "a" true) (pod "b" true)]}}
              {})]
      (is (true? (:passed? r))))))

;------------------------------------------------------------------------------ Layer 1
;; check-health-endpoint — pass / fail variations

(deftest check-health-endpoint-passes-with-all-passing-results-test
  (testing "Every health and smoke result passing ⇒ passes; counts reported"
    (let [r (sut/check-health-endpoint
              {:health-results [{:passed? true :url "http://x/h" :status-code 200}]
               :smoke-results [{:passed? true :command "curl x"}]} {})]
      (is (true? (:passed? r)))
      (is (= 1 (:health-checks-passed r)))
      (is (= 1 (:smoke-tests-passed r))))))

(deftest check-health-endpoint-passes-with-empty-results-test
  (testing "No health/smoke results at all ⇒ passes vacuously"
    (let [r (sut/check-health-endpoint {} {})]
      (is (true? (:passed? r))))))

(deftest check-health-endpoint-fails-on-failing-health-test
  (testing "Any failing health result ⇒ :health-check-failed error with status"
    (let [r (sut/check-health-endpoint
              {:health-results [{:passed? false :url "http://x/h" :status-code 503}]} {})]
      (is (false? (:passed? r)))
      (is (= :health-check-failed (get-in r [:errors 0 :type])))
      (is (= 503 (get-in r [:errors 0 :status-code]))))))

(deftest check-health-endpoint-fails-on-failing-smoke-test
  (testing "Health all pass but smoke fails ⇒ :smoke-test-failed error
            (health is checked first, then smoke)"
    (let [r (sut/check-health-endpoint
              {:health-results [{:passed? true :url "x" :status-code 200}]
               :smoke-results  [{:passed? false :command "ls" :stderr "no such"}]} {})]
      (is (false? (:passed? r)))
      (is (= :smoke-test-failed (get-in r [:errors 0 :type])))
      (is (= "ls" (get-in r [:errors 0 :command]))))))

(deftest check-health-endpoint-reads-nested-results-test
  (testing "Results nested under {:health {:results [...]}}/{:smoke {...}} recognized"
    (let [r (sut/check-health-endpoint
              {:health {:results [{:passed? true :url "x" :status-code 200}]}
               :smoke  {:results [{:passed? true :command "ok"}]}} {})]
      (is (true? (:passed? r)))
      (is (= 1 (:health-checks-passed r)))
      (is (= 1 (:smoke-tests-passed r))))))
