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

(ns ai.miniforge.workflow.anomaly.validate-checkpoint-data-test
  "Coverage for `schemas/validate-checkpoint-data` (anomaly-returning)
   and the `schemas/validate-checkpoint-data!` boundary helper that
   re-escalates the anomaly via thrown `ex-info`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.schemas :as schemas])
  (:import (clojure.lang ExceptionInfo)))

(def valid-checkpoint
  {:checkpoint/root "/tmp/checkpoints/run-1"
   :manifest {:workflow/id "run-1"
              :workflow/workflow-id :test-wf
              :workflow/workflow-version "1.0.0"
              :workflow/phases-completed [:start]
              :workflow/machine-snapshot-path "snapshot.edn"
              :workflow/phase-checkpoints {:start "start.edn"}
              :workflow/last-checkpoint-at "2026-01-01T00:00:00Z"}
   :machine-snapshot {:execution/id "run-1"
                      :execution/workflow-id :test-wf
                      :execution/workflow-version "1.0.0"
                      :execution/status :running
                      :execution/fsm-state :running
                      :execution/response-chain {}
                      :execution/errors []
                      :execution/artifacts []
                      :execution/metrics {:tokens 0}}
   :phase-results {:start {:success? true}}})

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)

(deftest validate-checkpoint-data-returns-value-on-valid
  (testing "valid checkpoint data passes through unchanged (no anomaly)"
    (let [result (schemas/validate-checkpoint-data valid-checkpoint)]
      (is (not (anomaly/anomaly? result)))
      (is (= valid-checkpoint result)))))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)

(deftest validate-checkpoint-data-returns-anomaly-on-invalid
  (testing "schema-invalid checkpoint yields :invalid-input anomaly"
    (let [result (schemas/validate-checkpoint-data {:not "valid"})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (let [data (:anomaly/data result)]
        (is (= {:not "valid"} (:value data)))
        (is (some? (:errors data)))))))

;------------------------------------------------------------------------------ Boundary helper escalation

(deftest validate-checkpoint-data!-throws-on-invalid
  (testing "validate-checkpoint-data! re-escalates the anomaly via ex-info"
    (try
      (schemas/validate-checkpoint-data! {:not "valid"})
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (= "Invalid checkpoint data" (.getMessage e)))
        (let [data (ex-data e)]
          (is (some? (:errors data)))
          (is (= {:not "valid"} (:value data))))))))

(deftest validate-checkpoint-data!-passes-valid-through
  (testing "validate-checkpoint-data! returns valid data unchanged"
    (is (= valid-checkpoint (schemas/validate-checkpoint-data! valid-checkpoint)))))
