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

(ns ai.miniforge.workflow.anomaly.build-initial-context-test
  "Coverage for `runner/build-initial-context-anomaly` and its deprecated
   throwing sibling `runner/build-initial-context`.

   The single failure mode this site protects is the N11 §7.4 invariant:
   `:governed` execution-mode requires a pre-acquired capsule executor +
   environment-id; missing either is `:invalid-input`. The happy path is
   covered indirectly by the existing `runner_pipeline_test`; here we
   only exercise the failure-path contract."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.runner :as runner])
  (:import (clojure.lang ExceptionInfo)))

(def workflow
  {:workflow/id :test
   :workflow/version "1.0.0"
   :workflow/phases [{:phase/id :start}]
   :workflow/pipeline [{:phase :start}]})

(def input {:repo-url "https://example.test/repo"})

;------------------------------------------------------------------------------ Happy path
;;
;; :local mode bypasses the capsule check and assembles a context. We
;; only assert non-anomaly here because the assembled map's full shape
;; is the responsibility of the existing runner_pipeline_test suite.

(deftest build-initial-context-anomaly-local-mode-returns-context
  (testing ":local mode produces a context map (not an anomaly)"
    (let [result (runner/build-initial-context-anomaly workflow input
                                                       {:execution-mode :local})]
      (is (not (anomaly/anomaly? result)))
      (is (= :local (:execution/mode result))))))

;------------------------------------------------------------------------------ Failure path

(deftest build-initial-context-anomaly-governed-without-capsule
  (testing ":governed mode with no executor + env-id yields :invalid-input anomaly"
    (let [result (runner/build-initial-context-anomaly workflow input
                                                       {:execution-mode :governed})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result))))))

(deftest build-initial-context-anomaly-data-carries-flags
  (testing "anomaly data carries enough triage info for the caller"
    (let [result (runner/build-initial-context-anomaly workflow input
                                                       {:execution-mode :governed})
          data   (:anomaly/data result)]
      (is (= :governed (:execution-mode data)))
      (is (false? (:has-executor? data)))
      (is (false? (:has-environment-id? data))))))

(deftest build-initial-context-anomaly-governed-with-executor-only
  (testing "executor without env-id is still an anomaly — both must be present"
    (let [result (runner/build-initial-context-anomaly workflow input
                                                       {:execution-mode :governed
                                                        :executor :some-exec})]
      (is (anomaly/anomaly? result))
      (is (true? (get-in result [:anomaly/data :has-executor?])))
      (is (false? (get-in result [:anomaly/data :has-environment-id?]))))))

;------------------------------------------------------------------------------ Throwing-variant compat
;;
;; `build-initial-context` is `defn-` (private) — the runner namespace
;; never intends it as a public API. We reach in via #'var to verify
;; the slingshot throw shape that legacy try+ callers (none upstream
;; today, but the catch site at runner.clj's top-level Object handler
;; wraps it) will continue to see.

(deftest build-initial-context-still-throws-on-governed-without-capsule
  (testing "deprecated throwing variant still throws via slingshot for legacy callers"
    (let [build! @#'runner/build-initial-context]
      (is (thrown? ExceptionInfo
                   (build! workflow input {:execution-mode :governed}))))))

(deftest build-initial-context-thrown-ex-data-preserves-slingshot-shape
  (testing "ex-data carries :anomalies.workflow/no-capsule-executor for try+ catches"
    (let [build! @#'runner/build-initial-context]
      (try
        (build! workflow input {:execution-mode :governed})
        (is false "should have thrown")
        (catch ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :anomalies.workflow/no-capsule-executor
                   (:anomaly/category data)))))))))
