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
  "Coverage for `runner-environment/check-executor-for-mode-anomaly`
   and its deprecated throwing sibling `assert-executor-for-mode!`.

   The N11 §7.4 invariant — :governed mode must not silently downgrade
   to a worktree fallback — is now expressed as an `:unavailable`
   anomaly returnable from non-boundary code, with the throwing wrapper
   preserved for slingshot callers."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.runner-environment :as env])
  (:import (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ Happy path

(deftest check-executor-anomaly-nil-when-executor-present
  (testing ":governed mode with a capsule executor returns nil (no anomaly)"
    (is (nil? (env/check-executor-for-mode-anomaly :some-executor :governed)))))

(deftest check-executor-anomaly-nil-for-local-mode
  (testing ":local mode without a capsule is fine — returns nil"
    (is (nil? (env/check-executor-for-mode-anomaly nil :local)))))

;------------------------------------------------------------------------------ Failure path

(deftest check-executor-anomaly-when-governed-without-capsule
  (testing ":governed mode with no capsule yields :unavailable anomaly"
    (let [result (env/check-executor-for-mode-anomaly nil :governed)]
      (is (anomaly/anomaly? result))
      (is (= :unavailable (:anomaly/type result))))))

(deftest check-executor-anomaly-data-carries-mode-and-hint
  (testing "anomaly data carries :mode and :hint for surface-level remediation"
    (let [result (env/check-executor-for-mode-anomaly nil :governed)
          data   (:anomaly/data result)]
      (is (= :governed (:mode data)))
      (is (string? (:hint data)))
      (is (seq (:hint data))))))

;------------------------------------------------------------------------------ Throwing-variant compat

(deftest assert-executor-still-noop-on-valid-args
  (testing "deprecated throwing variant is a no-op when an executor is present"
    (is (nil? (env/assert-executor-for-mode! :some-executor :governed)))))

(deftest assert-executor-still-noop-on-local-mode
  (testing "deprecated throwing variant is a no-op for :local mode"
    (is (nil? (env/assert-executor-for-mode! nil :local)))))

(deftest assert-executor-still-throws-when-governed-without-capsule
  (testing "deprecated throwing variant still throws via slingshot for legacy callers"
    (is (thrown? ExceptionInfo
                 (env/assert-executor-for-mode! nil :governed)))))

(deftest assert-executor-thrown-ex-data-preserves-slingshot-shape
  (testing "ex-data carries :anomalies.executor/unavailable for try+ catches"
    (try
      (env/assert-executor-for-mode! nil :governed)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :anomalies.executor/unavailable (:anomaly/category data)))
          (is (= :governed (:anomaly.executor/mode data)))
          (is (string? (:hint data))))))))
