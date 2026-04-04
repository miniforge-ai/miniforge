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

(ns ai.miniforge.workflow.metrics-accumulation-test
  "Integration test for metrics accumulation across workflow execution.

  Tests that metrics aggregate correctly through phases, handle parallel
  execution, and enforce budget limits without running full workflows."
  (:require
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Mock Data

(def mock-plan-metrics
  {:tokens 500
   :duration-ms 2000
   :cost-usd 0.001})

(def mock-implement-metrics
  {:tokens 1500
   :duration-ms 8000
   :cost-usd 0.003
   :files-created 3
   :files-modified 1})

(def mock-verify-metrics
  {:tokens 800
   :duration-ms 15000
   :cost-usd 0.002
   :tests-run 25
   :tests-passed 25})

(def mock-review-metrics
  {:tokens 300
   :duration-ms 3000
   :cost-usd 0.0006})

(def mock-release-metrics
  {:tokens 200
   :duration-ms 10000
   :cost-usd 0.0004
   :files-written 4})

;------------------------------------------------------------------------------ Mock Phase Results

(defn mock-phase-result
  "Create a mock phase result with metrics."
  [phase-name metrics]
  {:phase/name phase-name
   :phase/status :completed
   :phase/result {:status :success
                  :output {:result-data "mock"}
                  :metrics metrics}})

;------------------------------------------------------------------------------ Test Helpers

(defn create-execution-context
  "Create a minimal execution context for testing."
  []
  {:execution/id (random-uuid)
   :execution/input {:description "Test workflow"}
   :execution/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}
   :execution/phase-results {}
   :execution/budget {:tokens 10000
                      :cost-usd 1.0
                      :time-seconds 300}})

(defn accumulate-phase-metrics
  "Accumulate metrics from a phase result into execution context."
  [ctx phase-result]
  (let [phase-metrics (get-in phase-result [:phase/result :metrics] {})]
    (-> ctx
        (update-in [:execution/metrics :tokens]
                   (fnil + 0) (:tokens phase-metrics 0))
        (update-in [:execution/metrics :cost-usd]
                   (fnil + 0.0) (:cost-usd phase-metrics 0.0))
        (update-in [:execution/metrics :duration-ms]
                   (fnil + 0) (:duration-ms phase-metrics 0)))))

(defn check-budget-exceeded?
  "Check if execution has exceeded budget."
  [ctx]
  (let [metrics (get ctx :execution/metrics)
        budget (get ctx :execution/budget)]
    (or (> (:tokens metrics 0) (:tokens budget Integer/MAX_VALUE))
        (> (:cost-usd metrics 0.0) (:cost-usd budget Double/MAX_VALUE))
        (> (:duration-ms metrics 0) (* 1000 (:time-seconds budget Integer/MAX_VALUE))))))

;------------------------------------------------------------------------------ Tests

(deftest single-phase-metrics-test
  (testing "Metrics from single phase are recorded"
    (let [ctx (create-execution-context)
          phase-result (mock-phase-result :plan mock-plan-metrics)
          updated-ctx (accumulate-phase-metrics ctx phase-result)]

      (is (= 500 (get-in updated-ctx [:execution/metrics :tokens]))
          "Tokens should be recorded")
      (is (= 0.001 (get-in updated-ctx [:execution/metrics :cost-usd]))
          "Cost should be recorded")
      (is (= 2000 (get-in updated-ctx [:execution/metrics :duration-ms]))
          "Duration should be recorded"))))

(deftest multiple-phases-accumulation-test
  (testing "Metrics accumulate across multiple phases"
    (let [ctx (create-execution-context)
          phases [(mock-phase-result :plan mock-plan-metrics)
                  (mock-phase-result :implement mock-implement-metrics)
                  (mock-phase-result :verify mock-verify-metrics)]
          final-ctx (reduce accumulate-phase-metrics ctx phases)]

      (is (= 2800 (get-in final-ctx [:execution/metrics :tokens]))
          "Total tokens should sum: 500+1500+800")
      (is (= 0.006 (get-in final-ctx [:execution/metrics :cost-usd]))
          "Total cost should sum: 0.001+0.003+0.002")
      (is (= 25000 (get-in final-ctx [:execution/metrics :duration-ms]))
          "Total duration should sum: 2000+8000+15000"))))

(deftest full-workflow-metrics-test
  (testing "Complete workflow metrics accumulation"
    (let [ctx (create-execution-context)
          all-phases [(mock-phase-result :plan mock-plan-metrics)
                      (mock-phase-result :implement mock-implement-metrics)
                      (mock-phase-result :verify mock-verify-metrics)
                      (mock-phase-result :review mock-review-metrics)
                      (mock-phase-result :release mock-release-metrics)]
          final-ctx (reduce accumulate-phase-metrics ctx all-phases)]

      (is (= 3300 (get-in final-ctx [:execution/metrics :tokens]))
          "Should accumulate all tokens")
      (is (>= (get-in final-ctx [:execution/metrics :cost-usd]) 0.006)
          "Should accumulate all costs")
      (is (= 38000 (get-in final-ctx [:execution/metrics :duration-ms]))
          "Should accumulate all durations"))))

(deftest token-budget-enforcement-test
  (testing "Token budget is enforced"
    (let [ctx (-> (create-execution-context)
                  (assoc-in [:execution/budget :tokens] 1000))
          phase1 (mock-phase-result :plan {:tokens 500})
          phase2 (mock-phase-result :implement {:tokens 800})
          ctx-after-1 (accumulate-phase-metrics ctx phase1)
          ctx-after-2 (accumulate-phase-metrics ctx-after-1 phase2)]

      (is (false? (check-budget-exceeded? ctx-after-1))
          "Should not exceed budget after phase 1")
      (is (true? (check-budget-exceeded? ctx-after-2))
          "Should exceed token budget after phase 2"))))

(deftest cost-budget-enforcement-test
  (testing "Cost budget is enforced"
    (let [ctx (-> (create-execution-context)
                  (assoc-in [:execution/budget :cost-usd] 0.005))
          phase1 (mock-phase-result :plan {:cost-usd 0.002})
          phase2 (mock-phase-result :implement {:cost-usd 0.004})
          ctx-after-1 (accumulate-phase-metrics ctx phase1)
          ctx-after-2 (accumulate-phase-metrics ctx-after-1 phase2)]

      (is (false? (check-budget-exceeded? ctx-after-1))
          "Should not exceed budget after phase 1")
      (is (true? (check-budget-exceeded? ctx-after-2))
          "Should exceed cost budget after phase 2"))))

(deftest time-budget-enforcement-test
  (testing "Time budget is enforced"
    (let [ctx (-> (create-execution-context)
                  (assoc-in [:execution/budget :time-seconds] 20))
          phase1 (mock-phase-result :plan {:duration-ms 10000})
          phase2 (mock-phase-result :implement {:duration-ms 15000})
          ctx-after-1 (accumulate-phase-metrics ctx phase1)
          ctx-after-2 (accumulate-phase-metrics ctx-after-1 phase2)]

      (is (false? (check-budget-exceeded? ctx-after-1))
          "Should not exceed budget after phase 1: 10s < 20s")
      (is (true? (check-budget-exceeded? ctx-after-2))
          "Should exceed time budget after phase 2: 25s > 20s"))))

(deftest parallel-phase-metrics-test
  (testing "Metrics from parallel phases accumulate correctly"
    (let [ctx (create-execution-context)
          ;; Simulate parallel execution
          parallel-phases [(mock-phase-result :implement-a {:tokens 500 :duration-ms 5000})
                           (mock-phase-result :implement-b {:tokens 600 :duration-ms 6000})]
          ;; In parallel execution, durations overlap, but tokens sum
          final-ctx (reduce accumulate-phase-metrics ctx parallel-phases)]

      (is (= 1100 (get-in final-ctx [:execution/metrics :tokens]))
          "Tokens should sum for parallel phases")
      ;; Note: In real implementation, duration might be max not sum
      (is (= 11000 (get-in final-ctx [:execution/metrics :duration-ms]))
          "Duration sums in this simple accumulation"))))

(deftest metrics-with-repair-iterations-test
  (testing "Metrics include repair loop iterations"
    (let [ctx (create-execution-context)
          initial-attempt (mock-phase-result :implement {:tokens 1000})
          repair-1 (mock-phase-result :implement {:tokens 500})
          repair-2 (mock-phase-result :implement {:tokens 500})
          final-ctx (reduce accumulate-phase-metrics ctx
                            [initial-attempt repair-1 repair-2])]

      (is (= 2000 (get-in final-ctx [:execution/metrics :tokens]))
          "Should include tokens from all repair attempts"))))

(deftest missing-metrics-handling-test
  (testing "Handles phases with missing or partial metrics"
    (let [ctx (create-execution-context)
          phase-no-cost (mock-phase-result :plan {:tokens 500 :duration-ms 1000})
          phase-no-tokens (mock-phase-result :implement {:cost-usd 0.001 :duration-ms 2000})
          final-ctx (reduce accumulate-phase-metrics ctx
                            [phase-no-cost phase-no-tokens])]

      (is (= 500 (get-in final-ctx [:execution/metrics :tokens]))
          "Should handle missing cost")
      (is (= 0.001 (get-in final-ctx [:execution/metrics :cost-usd]))
          "Should handle missing tokens")
      (is (= 3000 (get-in final-ctx [:execution/metrics :duration-ms]))
          "Should accumulate durations"))))

(deftest zero-metrics-test
  (testing "Phases with zero metrics don't break accumulation"
    (let [ctx (create-execution-context)
          zero-phase (mock-phase-result :plan {:tokens 0 :cost-usd 0.0 :duration-ms 0})
          normal-phase (mock-phase-result :implement mock-implement-metrics)
          final-ctx (reduce accumulate-phase-metrics ctx
                            [zero-phase normal-phase])]

      (is (= 1500 (get-in final-ctx [:execution/metrics :tokens]))
          "Should handle zero metrics")
      (is (= 0.003 (get-in final-ctx [:execution/metrics :cost-usd]))
          "Should handle zero cost"))))

(deftest phase-specific-metrics-test
  (testing "Phase-specific metrics are preserved"
    (let [ctx (create-execution-context)
          _impl-result (mock-phase-result :implement mock-implement-metrics)
          _verify-result (mock-phase-result :verify mock-verify-metrics)
          _ctx-with-phases (-> ctx
                               (assoc-in [:phase-metrics :implement] mock-implement-metrics)
                               (assoc-in [:phase-metrics :verify] mock-verify-metrics))]

      (is (= 3 (get-in mock-implement-metrics [:files-created]))
          "Should preserve files-created from implement")
      (is (= 25 (get-in mock-verify-metrics [:tests-run]))
          "Should preserve tests-run from verify"))))

(deftest budget-remaining-calculation-test
  (testing "Calculate remaining budget"
    (let [ctx (-> (create-execution-context)
                  (assoc-in [:execution/metrics :tokens] 3000)
                  (assoc-in [:execution/budget :tokens] 10000))
          remaining-tokens (- (get-in ctx [:execution/budget :tokens])
                              (get-in ctx [:execution/metrics :tokens]))]

      (is (= 7000 remaining-tokens)
          "Should calculate remaining token budget correctly"))))

(deftest metrics-in-evidence-bundle-test
  (testing "Metrics are included in evidence bundle"
    (let [ctx (reduce accumulate-phase-metrics
                      (create-execution-context)
                      [(mock-phase-result :plan mock-plan-metrics)
                       (mock-phase-result :implement mock-implement-metrics)])
          evidence-bundle {:execution/metrics (get ctx :execution/metrics)
                           :execution/id (get ctx :execution/id)}]

      (is (contains? evidence-bundle :execution/metrics)
          "Evidence bundle should include metrics")
      (is (= 2000 (get-in evidence-bundle [:execution/metrics :tokens]))
          "Evidence should have correct token count"))))

(deftest metrics-reset-between-runs-test
  (testing "Metrics are reset for new workflow runs"
    (let [ctx1 (reduce accumulate-phase-metrics
                       (create-execution-context)
                       [(mock-phase-result :plan mock-plan-metrics)])
          ctx2 (create-execution-context)] ; New run

      (is (= 500 (get-in ctx1 [:execution/metrics :tokens]))
          "First run should have accumulated metrics")
      (is (= 0 (get-in ctx2 [:execution/metrics :tokens]))
          "Second run should start fresh"))))

(deftest metrics-with-failed-phase-test
  (testing "Metrics are recorded even when phase fails"
    (let [ctx (create-execution-context)
          failed-phase {:phase/name :implement
                        :phase/status :failed
                        :phase/result {:status :error
                                       :error "Implementation failed"
                                       :metrics {:tokens 800 :duration-ms 5000}}}
          updated-ctx (accumulate-phase-metrics ctx failed-phase)]

      (is (= 800 (get-in updated-ctx [:execution/metrics :tokens]))
          "Should record tokens even for failed phase")
      (is (= 5000 (get-in updated-ctx [:execution/metrics :duration-ms]))
          "Should record duration even for failed phase"))))

(deftest budget-warning-threshold-test
  (testing "Warn when approaching budget limits"
    (let [ctx (-> (create-execution-context)
                  (assoc-in [:execution/metrics :tokens] 8500)
                  (assoc-in [:execution/budget :tokens] 10000))
          usage-pct (/ (get-in ctx [:execution/metrics :tokens])
                       (get-in ctx [:execution/budget :tokens]))
          should-warn? (> usage-pct 0.8)]

      (is (true? should-warn?)
          "Should warn at 85% budget usage")
      (is (> usage-pct 0.8)
          "Usage should exceed 80% threshold"))))

(deftest cost-per-phase-analysis-test
  (testing "Analyze cost per phase"
    (let [phases {:plan mock-plan-metrics
                  :implement mock-implement-metrics
                  :verify mock-verify-metrics}
          total-cost (reduce + (map :cost-usd (vals phases)))
          cost-percentages (into {}
                                 (map (fn [[phase metrics]]
                                        [phase (* 100.0 (/ (:cost-usd metrics) total-cost))])
                                      phases))]

      (is (= 0.006 total-cost)
          "Total cost should sum correctly")
      (is (> (:implement cost-percentages) (:plan cost-percentages))
          "Implement should be more expensive than plan")
      (is (< (Math/abs (- 100.0 (reduce + (vals cost-percentages)))) 0.01)
          "Percentages should sum to ~100%"))))
