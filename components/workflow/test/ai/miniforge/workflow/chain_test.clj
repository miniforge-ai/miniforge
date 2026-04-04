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

(ns ai.miniforge.workflow.chain-test
  "Tests for workflow chain executor."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.chain :as chain]
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Layer 0
;; resolve-binding tests

(deftest resolve-binding-string-literal-test
  (testing "string literal passes through unchanged"
    (is (= "hello" (chain/resolve-binding "hello" nil nil)))
    (is (= "some/path" (chain/resolve-binding "some/path" {} {})))))

(deftest resolve-binding-chain-input-test
  (testing ":chain/input.KEY reads from chain input"
    (is (= "my-task"
           (chain/resolve-binding :chain/input.task nil {:task "my-task"})))
    (is (= 42
           (chain/resolve-binding :chain/input.count nil {:count 42}))))
  (testing ":chain/input.KEY returns nil for missing key"
    (is (nil? (chain/resolve-binding :chain/input.missing nil {:task "x"})))))

(deftest resolve-binding-vector-path-test
  (testing "vector path navigates into prev-output"
    (let [prev-output {:phase-results {:plan {:success? true :data "planned"}}
                       :artifacts [{:title "doc"}]
                       :last-phase-result {:success? true :output "done"}}]
      (testing ":prev/phase-results root"
        (is (= {:success? true :data "planned"}
               (chain/resolve-binding [:prev/phase-results :plan] prev-output nil))))
      (testing ":prev/artifacts root"
        (is (= [{:title "doc"}]
               (chain/resolve-binding [:prev/artifacts] prev-output nil))))
      (testing ":prev/last-phase-result root with path"
        (is (= "done"
               (chain/resolve-binding [:prev/last-phase-result :output] prev-output nil)))))))

(deftest resolve-binding-keyword-fallback-test
  (testing "plain keyword reads from chain input"
    (is (= "value" (chain/resolve-binding :my-key nil {:my-key "value"})))
    (is (nil? (chain/resolve-binding :missing nil {:other "x"})))))

;------------------------------------------------------------------------------ Layer 1
;; resolve-bindings tests

(deftest resolve-bindings-test
  (testing "resolves a full bindings map"
    (let [bindings {:task :chain/input.task
                    :plan [:prev/last-phase-result :plan]
                    :label "static-label"
                    :extra :chain/input.extra}
          prev-output {:last-phase-result {:plan "the plan" :other "x"}}
          chain-input {:task "build feature" :extra "bonus"}
          result (chain/resolve-bindings bindings prev-output chain-input)]
      (is (= "build feature" (:task result)))
      (is (= "the plan" (:plan result)))
      (is (= "static-label" (:label result)))
      (is (= "bonus" (:extra result))))))

;------------------------------------------------------------------------------ Layer 2
;; run-chain tests

(deftest run-chain-two-steps-test
  (testing "chain with 2 steps where step 2 receives step 1 output"
    (let [call-log (atom [])
          chain-def {:chain/id :test-chain
                     :chain/version "1.0.0"
                     :chain/description "Test chain"
                     :chain/steps
                     [{:step/id :step-1
                       :step/workflow-id :workflow-a
                       :step/input-bindings {:task :chain/input.task}}
                      {:step/id :step-2
                       :step/workflow-id :workflow-b
                       :step/input-bindings {:plan [:prev/last-phase-result :plan]
                                             :task :chain/input.task}}]}]
      (with-redefs [runner/run-pipeline
                    (fn [_workflow input _opts]
                      (swap! call-log conj input)
                      {:execution/status :completed
                       :execution/output {:artifacts [{:title (:task input)}]
                                          :phase-results {}
                                          :last-phase-result {:success? true
                                                              :plan (str "plan-for-" (:task input))}
                                          :status :completed}})
                    ;; Mock load-workflow via requiring-resolve
                    ;; We need to mock at the requiring-resolve level
                    ]
        ;; Mock load-workflow by redefining requiring-resolve behavior
        (let [orig-requiring-resolve requiring-resolve]
          (with-redefs [requiring-resolve
                        (fn [sym]
                          (if (= sym 'ai.miniforge.workflow.interface/load-workflow)
                            (fn [_wf-id _version _opts]
                              {:workflow {:workflow/id :mock
                                          :workflow/pipeline [{:phase :plan}]}
                               :source :mock})
                            (orig-requiring-resolve sym)))]
            (let [result (chain/run-chain chain-def {:task "build-login"} {})]
              (is (= :test-chain (:chain/id result)))
              (is (= :completed (:chain/status result)))
              (is (= 2 (count (:chain/step-results result))))
              (is (nat-int? (:chain/duration-ms result)))

              ;; Verify step 1 received chain input
              (is (= {:task "build-login"} (first @call-log)))

              ;; Verify step 2 received resolved bindings from step 1 output
              (let [step2-input (second @call-log)]
                (is (= "build-login" (:task step2-input)))
                (is (= "plan-for-build-login" (:plan step2-input))))

              ;; Verify step results structure
              (is (= :step-1 (get-in result [:chain/step-results 0 :step/id])))
              (is (= :step-2 (get-in result [:chain/step-results 1 :step/id])))
              (is (= :completed (get-in result [:chain/step-results 0 :step/status])))
              (is (= :completed (get-in result [:chain/step-results 1 :step/status]))))))))))

(deftest run-chain-stops-on-failure-test
  (testing "chain stops immediately when a step fails"
    (let [call-count (atom 0)
          chain-def {:chain/id :fail-chain
                     :chain/version "1.0.0"
                     :chain/description "Chain that fails"
                     :chain/steps
                     [{:step/id :step-1
                       :step/workflow-id :workflow-a
                       :step/input-bindings {:task :chain/input.task}}
                      {:step/id :step-2
                       :step/workflow-id :workflow-b
                       :step/input-bindings {:task :chain/input.task}}]}]
      (with-redefs [runner/run-pipeline
                    (fn [_workflow _input _opts]
                      (swap! call-count inc)
                      {:execution/status :failed
                       :execution/output {:artifacts []
                                          :phase-results {}
                                          :last-phase-result {:success? false}
                                          :status :failed}})]
        (let [orig-requiring-resolve requiring-resolve]
          (with-redefs [requiring-resolve
                        (fn [sym]
                          (if (= sym 'ai.miniforge.workflow.interface/load-workflow)
                            (fn [_wf-id _version _opts]
                              {:workflow {:workflow/id :mock
                                          :workflow/pipeline [{:phase :plan}]}
                               :source :mock})
                            (orig-requiring-resolve sym)))]
            (let [result (chain/run-chain chain-def {:task "doomed"} {})]
              (is (= :failed (:chain/status result)))
              (is (= 1 (count (:chain/step-results result))))
              (is (= 1 @call-count) "step 2 should never execute")
              (is (= :failed (get-in result [:chain/step-results 0 :step/status])))
              (is (nat-int? (:chain/duration-ms result))))))))))
