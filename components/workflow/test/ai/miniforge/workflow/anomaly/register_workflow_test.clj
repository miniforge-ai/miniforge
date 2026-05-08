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

(ns ai.miniforge.workflow.anomaly.register-workflow-test
  "Coverage for the registry registration anomaly-returning helpers
   (`missing-workflow-id-anomaly`, `validate-workflow-registration`)
   and the `register-workflow!` boundary that escalates them to
   slingshot."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.registry :as registry]
            [ai.miniforge.workflow.validator :as validator])
  (:import (clojure.lang ExceptionInfo)))

(def workflow-no-id
  {:workflow/version "1.0.0"
   :workflow/name "Anonymous"
   :workflow/phases [{:phase/id :start}]})

;------------------------------------------------------------------------------ missing-workflow-id-anomaly

(deftest missing-workflow-id-anomaly-returns-nil-when-id-present
  (testing "id present → nil (no anomaly)"
    (is (nil? (registry/missing-workflow-id-anomaly {:workflow/id :ok})))))

(deftest missing-workflow-id-anomaly-returns-anomaly-when-id-missing
  (testing "id absent → :invalid-input anomaly carrying :workflow"
    (let [result (registry/missing-workflow-id-anomaly workflow-no-id)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= workflow-no-id (get-in result [:anomaly/data :workflow]))))))

;------------------------------------------------------------------------------ validate-workflow-registration

(deftest validate-workflow-registration-returns-anomaly-on-invalid
  (testing "validator errors → :invalid-input anomaly carrying :validation/errors"
    (with-redefs [validator/validate-workflow
                  (fn [_] {:valid? false :errors [{:msg "bad"}]})]
      (let [result (registry/validate-workflow-registration {:workflow/id :x})]
        (is (anomaly/anomaly? result))
        (is (= :invalid-input (:anomaly/type result)))
        (is (= :x (get-in result [:anomaly/data :workflow-id])))
        (is (seq (get-in result [:anomaly/data :validation/errors])))))))

(deftest validate-workflow-registration-returns-workflow-on-valid
  (testing "validator clean → returns the workflow itself (no anomaly)"
    (with-redefs [validator/validate-workflow
                  (fn [_] {:valid? true :errors []})]
      (let [wf {:workflow/id :ok}
            result (registry/validate-workflow-registration wf)]
        (is (not (anomaly/anomaly? result)))
        (is (= wf result))))))

;------------------------------------------------------------------------------ Boundary escalation through register-workflow!

(deftest register-workflow!-throws-on-missing-id
  (registry/clear-registry!)
  (testing "register-workflow! escalates missing-id anomaly under :anomalies/incorrect"
    (try
      (registry/register-workflow! workflow-no-id)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :anomalies/incorrect (:anomaly/category data))))))))

(deftest register-workflow!-throws-on-validator-failure
  (registry/clear-registry!)
  (testing "register-workflow! escalates validator anomaly under :anomalies.workflow/invalid-config"
    (with-redefs [validator/validate-workflow
                  (fn [_] {:valid? false :errors [{:msg "bad"}]})]
      (try
        (registry/register-workflow! {:workflow/id :bad})
        (is false "should have thrown")
        (catch ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :anomalies.workflow/invalid-config (:anomaly/category data)))))))))

(deftest register-workflow!-happy-path
  (registry/clear-registry!)
  (testing "valid workflow is stored and returned unchanged"
    (with-redefs [validator/validate-workflow
                  (fn [_] {:valid? true :errors []})]
      (let [wf {:workflow/id :ok-test :workflow/version "1.0.0"}
            result (registry/register-workflow! wf)]
        (is (= wf result))
        (is (registry/workflow-exists? :ok-test))))))
