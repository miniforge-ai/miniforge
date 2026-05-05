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

(ns ai.miniforge.workflow.anomaly.validate-and-cache-workflow-test
  "Coverage for `loader/validate-and-cache-workflow` (anomaly-returning)
   and the `loader/load-workflow` boundary that escalates validation
   anomalies to slingshot throws under
   `:anomalies.workflow/invalid-config`.

   Also covers the `:anomalies/not-found` boundary for the
   missing-everywhere case at the same call site."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.validator :as validator])
  (:import (clojure.lang ExceptionInfo)))

(def valid-workflow
  {:workflow/id :test-anomaly-wf
   :workflow/version "1.0.0"
   :workflow/name "Test"
   :workflow/description "Anomaly test workflow"
   :workflow/phases [{:phase/id :start
                      :phase/type :init}]})

(defn- always-valid [_] {:valid? true :errors []})
(defn- always-invalid [_] {:valid? false :errors [{:path [:workflow/id] :message "bad"}]})

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)

(deftest validate-and-cache-returns-result-map-on-success
  (loader/clear-cache!)
  (testing "valid workflow yields {:workflow :source :validation}, no anomaly"
    (with-redefs [validator/validate-workflow always-valid]
      (let [result (loader/validate-and-cache-workflow valid-workflow
                                                       :test-anomaly-wf
                                                       "1.0.0"
                                                       false)]
        (is (not (anomaly/anomaly? result)))
        (is (= valid-workflow (:workflow result)))
        (is (contains? #{:resource :store} (:source result)))
        (is (true? (get-in result [:validation :valid?])))))))

(deftest validate-and-cache-skip-validation-bypasses-validator
  (loader/clear-cache!)
  (testing "skip-validation? short-circuits the validator and caches"
    (let [result (loader/validate-and-cache-workflow valid-workflow
                                                     :test-anomaly-wf
                                                     "1.0.0"
                                                     true)]
      (is (not (anomaly/anomaly? result)))
      (is (= valid-workflow (:workflow result))))))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)

(deftest validate-and-cache-returns-anomaly-on-validation-failure
  (loader/clear-cache!)
  (testing "validator errors yield :invalid-input anomaly with diagnostics"
    (with-redefs [validator/validate-workflow always-invalid]
      (let [result (loader/validate-and-cache-workflow valid-workflow
                                                       :test-anomaly-wf
                                                       "1.0.0"
                                                       false)]
        (is (anomaly/anomaly? result))
        (is (= :invalid-input (:anomaly/type result)))
        (let [data (:anomaly/data result)]
          (is (= :test-anomaly-wf (:workflow-id data)))
          (is (= "1.0.0" (:version data)))
          (is (seq (:errors data))))))))

(deftest validate-and-cache-failure-does-not-cache
  (loader/clear-cache!)
  (testing "failed validation must not write to the workflow cache"
    (with-redefs [validator/validate-workflow always-invalid]
      (loader/validate-and-cache-workflow valid-workflow :test-anomaly-wf "1.0.0" false)
      (is (nil? (loader/try-load-from-cache [:test-anomaly-wf "1.0.0"] false))))))

;------------------------------------------------------------------------------ Boundary escalation through load-workflow

(deftest load-workflow-throws-invalid-config-on-validator-failure
  (loader/clear-cache!)
  (testing "load-workflow escalates a validator anomaly to slingshot under :anomalies.workflow/invalid-config"
    (with-redefs [loader/load-from-resource (fn [_ _] valid-workflow)
                  validator/validate-workflow always-invalid]
      (try
        (loader/load-workflow :test-anomaly-wf "1.0.0" {})
        (is false "should have thrown")
        (catch ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :anomalies.workflow/invalid-config (:anomaly/category data)))))))))

(deftest load-workflow-throws-not-found-when-missing-everywhere
  (loader/clear-cache!)
  (testing "load-workflow escalates the missing-everywhere case to :anomalies/not-found"
    (with-redefs [loader/load-from-resource (fn [_ _] nil)
                  loader/load-from-store (fn [_ _ _] nil)]
      (try
        (loader/load-workflow :ghost-workflow "0.0.1" {})
        (is false "should have thrown")
        (catch ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :anomalies/not-found (:anomaly/category data)))
            (is (= :ghost-workflow (:workflow-id data)))))))))
