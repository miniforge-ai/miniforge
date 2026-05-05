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

(ns ai.miniforge.workflow.anomaly.check-executor-for-mode-test
  "Coverage for `runner-environment/check-executor-for-mode`
   (anomaly-returning) and the boundary throw inlined inside
   `acquire-execution-environment!` (the only escalation site for the
   N11 §7.4 invariant)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.runner-environment :as env])
  (:import (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ Anomaly-returning happy path

(deftest check-executor-nil-when-executor-present
  (testing ":governed mode with a capsule executor returns nil (no anomaly)"
    (is (nil? (env/check-executor-for-mode :some-executor :governed)))))

(deftest check-executor-nil-for-local-mode
  (testing ":local mode without a capsule is fine — returns nil"
    (is (nil? (env/check-executor-for-mode nil :local)))))

;------------------------------------------------------------------------------ Anomaly-returning failure path

(deftest check-executor-anomaly-when-governed-without-capsule
  (testing ":governed mode with no capsule yields :unavailable anomaly"
    (let [result (env/check-executor-for-mode nil :governed)]
      (is (anomaly/anomaly? result))
      (is (= :unavailable (:anomaly/type result))))))

(deftest check-executor-anomaly-data-carries-mode-and-hint
  (testing "anomaly data carries :mode and :hint for surface-level remediation"
    (let [result (env/check-executor-for-mode nil :governed)
          data   (:anomaly/data result)]
      (is (= :governed (:mode data)))
      (is (string? (:hint data)))
      (is (seq (:hint data))))))

;------------------------------------------------------------------------------ Boundary escalation via acquire-execution-environment!
;;
;; `acquire-execution-environment!` is the only place that escalates a
;; check-executor-for-mode anomaly to a thrown exception. Tests pin
;; the slingshot ex-info shape that legacy try+ callers above this
;; boundary (CLI / MCP / orchestrator) depend on.

(deftest acquire-execution-environment-throws-on-governed-without-capsule
  (testing "governed mode with no capsule available propagates a slingshot throw"
    (with-redefs [env/dag-executor-fns
                  (constantly
                   {:create-registry (constantly :stub-registry)
                    :select-exec     (constantly nil)        ; no executor
                    :acquire-env!    (constantly nil)
                    :executor-type   (constantly nil)
                    :result-ok?      (constantly false)
                    :result-unwrap   (constantly nil)})]
      (is (thrown? ExceptionInfo
                   (env/acquire-execution-environment!
                    :workflow-id-1
                    {:execution-mode :governed}))))))

(deftest acquire-execution-environment-thrown-ex-data-preserves-slingshot-shape
  (testing "ex-data carries :anomalies.executor/unavailable for try+ catches"
    (with-redefs [env/dag-executor-fns
                  (constantly
                   {:create-registry (constantly :stub-registry)
                    :select-exec     (constantly nil)
                    :acquire-env!    (constantly nil)
                    :executor-type   (constantly nil)
                    :result-ok?      (constantly false)
                    :result-unwrap   (constantly nil)})]
      (try
        (env/acquire-execution-environment!
         :workflow-id-1
         {:execution-mode :governed})
        (is false "should have thrown")
        (catch ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :anomalies.executor/unavailable (:anomaly/category data)))
            (is (= :governed (:anomaly.executor/mode data)))
            (is (string? (:hint data)))))))))
