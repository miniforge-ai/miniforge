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

(ns ai.miniforge.workflow.anomaly.workflow-characteristics-test
  "Coverage for `registry/try-workflow-characteristics`
   (anomaly-returning) and the `registry/workflow-characteristics`
   boundary that escalates schema-validation anomalies to slingshot
   under `:anomalies.workflow/invalid-config`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.registry :as registry])
  (:import (clojure.lang ExceptionInfo)))

(def valid-workflow
  ;; Phases include "review" and "test" tokens so that the
  ;; `some str/includes?` lookups in compute-characteristics return
  ;; truthy values (the schema requires :has-review and :has-testing
  ;; to be plain booleans; existing code uses `some`, which yields
  ;; `nil` rather than `false` when no phase id contains the token).
  {:workflow/id :wc-test
   :workflow/version "1.0.0"
   :workflow/name "WC test"
   :workflow/description "Workflow characteristics test"
   :workflow/phases [{:phase/id :review-step} {:phase/id :test-step}]
   :workflow/task-types [:feature]})

(def invalid-workflow
  ;; Missing :workflow/id, :workflow/version, etc. — schema rejects.
  {:workflow/phases []})

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)

(deftest try-workflow-characteristics-returns-map-on-valid
  (testing "valid workflow yields a characteristics map (no anomaly)"
    (let [result (registry/try-workflow-characteristics valid-workflow)]
      (is (not (anomaly/anomaly? result)))
      (is (= :wc-test (:id result)))
      (is (= :simple (:complexity result)))
      (is (boolean? (:has-review result))))))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)

(deftest try-workflow-characteristics-returns-anomaly-on-invalid
  (testing "schema-invalid characteristics yield :invalid-input anomaly"
    (let [result (registry/try-workflow-characteristics invalid-workflow)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (let [data (:anomaly/data result)]
        (is (some? (:characteristics data)))
        (is (some? (:errors data)))))))

;------------------------------------------------------------------------------ Boundary escalation

(deftest workflow-characteristics-throws-on-invalid
  (testing "workflow-characteristics escalates the anomaly to slingshot"
    (try
      (registry/workflow-characteristics invalid-workflow)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :anomalies.workflow/invalid-config (:anomaly/category data))))))))
