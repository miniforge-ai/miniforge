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

(ns ai.miniforge.workflow.runner-integration-test
  "Integration tests for the workflow runner that execute real phase pipelines."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.runner :as runner]
   ;; Require phase implementations
   [ai.miniforge.phase-software-factory.plan]
   [ai.miniforge.phase-software-factory.implement]
   [ai.miniforge.phase-software-factory.verify]
   [ai.miniforge.phase-software-factory.review]
   [ai.miniforge.phase-software-factory.release]
   ;; Require gate implementations
   [ai.miniforge.gate.syntax]
   [ai.miniforge.gate.lint]
   [ai.miniforge.gate.test]
   [ai.miniforge.gate.policy]))

(defn with-mocked-test-runner
  "Run body-fn with run-tests! mocked to prevent recursive bb test."
  [body-fn]
  (let [run-var (resolve 'ai.miniforge.phase-software-factory.verify/run-tests!)]
    (with-redefs-fn
      {run-var (fn [& _args] {:passed? true :test-count 1 :fail-count 0 :error-count 0})}
      body-fn)))

(deftest run-pipeline-max-phases-test
  (testing "run-pipeline respects max phases option"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {:max-phases 50})]
      (is (= :completed (:execution/status result))))))

(deftest response-chain-records-phases-test
  (testing "response chain records phase results"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :plan}
                                        {:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {})
          chain (:execution/response-chain result)]
      (is (= :completed (:execution/status result)))
      (is (>= (count (:response-chain chain)) 2))
      (is (true? (:succeeded? chain))))))
