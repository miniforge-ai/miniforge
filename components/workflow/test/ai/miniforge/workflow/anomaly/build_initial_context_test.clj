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
  "Coverage for `runner/build-initial-context` (anomaly-returning) and
   its boundary escalation in `runner/run-pipeline`.

   The single failure mode this site protects is the N11 §7.4
   invariant: `:governed` execution-mode requires a pre-acquired
   capsule executor + environment-id; missing either is
   `:invalid-input`. The boundary at `run-pipeline` rethrows via
   slingshot so external callers (CLI / MCP / orchestrator) keep
   their existing exception-shaped contract."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.runner :as runner]))

(def workflow
  {:workflow/id :test
   :workflow/version "1.0.0"
   :workflow/phases [{:phase/id :start}]
   :workflow/pipeline [{:phase :start}]})

(def input {:repo-url "https://example.test/repo"})

;------------------------------------------------------------------------------ Anomaly-returning happy path
;;
;; :local mode bypasses the capsule check and assembles a context. We
;; only assert non-anomaly here because the assembled map's full shape
;; is the responsibility of the existing runner_pipeline_test suite.

(deftest build-initial-context-local-mode-returns-context
  (testing ":local mode produces a context map (not an anomaly)"
    (let [result (runner/build-initial-context workflow input
                                                {:execution-mode :local})]
      (is (not (anomaly/anomaly? result)))
      (is (= :local (:execution/mode result))))))

;------------------------------------------------------------------------------ Anomaly-returning failure path

(deftest build-initial-context-governed-without-capsule
  (testing ":governed mode with no executor + env-id yields :invalid-input anomaly"
    (let [result (runner/build-initial-context workflow input
                                                {:execution-mode :governed})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result))))))

(deftest build-initial-context-anomaly-data-carries-flags
  (testing "anomaly data carries enough triage info for the caller"
    (let [result (runner/build-initial-context workflow input
                                                {:execution-mode :governed})
          data   (:anomaly/data result)]
      (is (= :governed (:execution-mode data)))
      (is (false? (:has-executor? data)))
      (is (false? (:has-environment-id? data))))))

(deftest build-initial-context-governed-with-executor-only
  (testing "executor without env-id is still an anomaly — both must be present"
    (let [result (runner/build-initial-context workflow input
                                                {:execution-mode :governed
                                                 :executor :some-exec})]
      (is (anomaly/anomaly? result))
      (is (true? (get-in result [:anomaly/data :has-executor?])))
      (is (false? (get-in result [:anomaly/data :has-environment-id?]))))))

;------------------------------------------------------------------------------ Boundary escalation
;;
;; `run-pipeline` is the runner's escalation point: external callers
;; expect either a final context map or a thrown exception. A governed
;; misconfiguration becomes a slingshot
;; `:anomalies.workflow/no-capsule-executor` ex-info at the
;; `run-pipeline` boundary.
;;
;; That escalation is *not* exercised here because `run-pipeline`'s
;; happy path runs `acquire-environment` first, which has its own
;; governed-mode throw at `acquire-execution-environment!` (covered by
;; `check_executor_for_mode_test.clj`). For an end-to-end test of the
;; build-initial-context boundary throw specifically, the caller would
;; need to stub `acquire-environment` so the no-capsule-executor path
;; surfaces in `run-pipeline`'s let bindings rather than upstream. The
;; runner's existing pipeline tests cover that integration.
