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
   its failed-context conversion in `runner/run-pipeline`.

   The single failure mode this site protects is the N11 §7.4
   invariant: `:governed` execution-mode requires a pre-acquired
   capsule executor + environment-id; missing either is
   `:invalid-input`. `run-pipeline` converts that anomaly to a failed
   execution context so callers receive the runner's normal result
   shape."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.runner :as runner]))

(def workflow
  {:workflow/id :test
   :workflow/version "1.0.0"
   :workflow/phases []
   :workflow/pipeline []})

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

;------------------------------------------------------------------------------ run-pipeline conversion
;;
;; `run-pipeline` runs `acquire-environment` before
;; `build-initial-context`; the test below stubs the private acquire
;; helper so the context-building anomaly is exercised directly rather
;; than being masked by runner-environment's separate governed-mode
;; acquisition checks.

(deftest run-pipeline-context-anomaly-returns-failed-context
  (testing "run-pipeline converts build-initial-context anomaly to failed context"
    (with-redefs-fn {#'runner/acquire-environment
                     (fn [_workflow _input opts] [nil opts])}
      (fn []
        (let [result (runner/run-pipeline workflow input
                                          {:execution-mode :governed
                                           :skip-lifecycle-events true})]
          (is (= :failed (:execution/status result)))
          (is (some #(= :initial-context-anomaly (:type %))
                    (:execution/errors result)))
          (is (= :governed
                 (-> result :execution/errors first :data :execution-mode))))))))
