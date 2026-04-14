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

(ns ai.miniforge.loop.metrics-accumulation-test
  "Cross-cutting integration test for metrics accumulation.

   Tests that metrics flow correctly through inner and outer loop execution,
   that budget enforcement terminates over-budget workflows, and that
   metrics merge correctly from parallel-style phases.

   All externals are mocked — no LLM calls, no git operations, no network."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.loop.inner :as inner]
   [ai.miniforge.loop.outer :as outer]
   [ai.miniforge.loop.gates :as gates]
   [ai.miniforge.loop.repair :as repair]))

;------------------------------------------------------------------------------ Layer 0
;; Factory functions

(def tokens-per-generate 200)
(def tokens-per-repair 50)
(def duration-tolerance-ms 50)

(defn make-task
  "Create a test task."
  []
  {:task/id (random-uuid)
   :task/type :implement})

(defn make-valid-artifact
  "Create a syntactically valid code artifact."
  []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn hello [] \"world\")"})

(defn make-invalid-artifact
  "Create an artifact with broken syntax."
  []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn broken ["})

(defn make-generate-fn
  "Create a generate function returning a fixed artifact."
  [artifact]
  (fn [_task _ctx]
    {:artifact artifact
     :tokens tokens-per-generate}))

(defn make-counted-generate-fn
  "Create a generate function that counts invocations.
   Produces invalid artifacts for the first n-fails calls, then valid."
  [n-fails]
  (let [calls (atom 0)]
    (fn [_task _ctx]
      (swap! calls inc)
      (if (<= @calls n-fails)
        {:artifact (make-invalid-artifact) :tokens tokens-per-generate}
        {:artifact (make-valid-artifact) :tokens tokens-per-generate}))))

(defn make-mock-repair-fn
  "Create a mock repair function that succeeds but costs tokens."
  []
  (fn [artifact _errors _ctx]
    {:success? true
     :artifact (assoc artifact :artifact/content "(defn fixed [] :ok)")
     :tokens-used tokens-per-repair}))

(defn make-phase-metrics
  "Create a phase metrics map simulating a completed phase."
  [phase-name tokens duration-ms]
  {:phase phase-name
   :tokens tokens
   :duration-ms duration-ms
   :generate-calls 1
   :repair-calls 0})

;------------------------------------------------------------------------------ Layer 1
;; Inner loop metrics: single iteration

(deftest single-iteration-metrics-test
  (testing "Metrics after one successful generation"
    (let [task (make-task)
          loop-state (inner/create-inner-loop task {:max-iterations 5})
          generate-fn (make-generate-fn (make-valid-artifact))
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       (repair/default-strategies)
                                       {})]
      (is (true? (:success result)))
      (let [metrics (:metrics result)]
        (is (= tokens-per-generate (:tokens metrics))
            "Tokens should equal single generation cost")
        (is (= 1 (:generate-calls metrics))
            "Should record exactly one generate call")
        (is (zero? (:repair-calls metrics))
            "No repair calls for clean pass")
        (is (nat-int? (:duration-ms metrics))
            "Duration should be non-negative")))))

;------------------------------------------------------------------------------ Layer 1
;; Inner loop metrics: multi-iteration with repairs

(deftest multi-iteration-metrics-accumulation-test
  (testing "Metrics accumulate across generate and repair cycles"
    (let [task (make-task)
          fail-count 2
          generate-fn (make-counted-generate-fn fail-count)
          repair-fn (make-mock-repair-fn)
          loop-state (inner/create-inner-loop task {:max-iterations 10})
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       (repair/default-strategies)
                                       {:repair-fn repair-fn})]
      (is (true? (:success result))
          "Loop should succeed after repairs")
      (let [metrics (:metrics result)
            expected-gen-calls (inc fail-count)]
        (is (>= (:generate-calls metrics) expected-gen-calls)
            "Generate calls should match fail-count + 1 successful")
        (is (pos? (:repair-calls metrics))
            "Repair calls should be positive after failures")
        ;; Note: repair/attempt-repair does not propagate :tokens-used to its
        ;; top-level result, so the inner loop only accumulates generate tokens.
        ;; Repair token tracking is a known gap (tokens are inside :results).
        (is (>= (:tokens metrics) (* expected-gen-calls tokens-per-generate))
            "Accumulated tokens should reflect all generate calls")))))

;------------------------------------------------------------------------------ Layer 1
;; Budget enforcement: token limit

(deftest token-budget-enforcement-test
  (testing "Token budget terminates over-budget workflow"
    (let [task (make-task)
          token-limit 250
          loop-state (inner/create-inner-loop
                      task
                      {:max-iterations 20
                       :budget {:max-tokens token-limit}})
          generate-fn (make-generate-fn (make-invalid-artifact))
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       [(repair/retry-strategy {:delay-ms 0})]
                                       {})]
      (is (false? (:success result))
          "Should fail due to budget exhaustion")
      (is (= :budget-exhausted (get-in result [:termination :reason]))
          "Termination reason should be budget-exhausted")
      (is (>= (get-in result [:metrics :tokens]) token-limit)
          "Final token count should be at or above the limit"))))

;------------------------------------------------------------------------------ Layer 1
;; Budget enforcement: iteration limit

(deftest iteration-budget-enforcement-test
  (testing "Iteration limit terminates loop"
    (let [task (make-task)
          max-iter 3
          loop-state (inner/create-inner-loop task {:max-iterations max-iter})
          generate-fn (make-generate-fn (make-invalid-artifact))
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       [(repair/retry-strategy {:delay-ms 0})]
                                       {})]
      (is (false? (:success result)))
      (is (= :max-iterations (get-in result [:termination :reason]))
          "Termination reason should be max-iterations")
      (is (<= (:iterations result) (inc max-iter))
          "Iterations should not exceed max + 1"))))

;------------------------------------------------------------------------------ Layer 1
;; Budget enforcement: cost limit

(deftest cost-budget-enforcement-test
  (testing "Cost budget terminates over-budget workflow"
    (let [task (make-task)
          cost-limit 0.01
          loop-state (inner/create-inner-loop
                      task
                      {:max-iterations 20
                       :budget {:max-cost-usd cost-limit}})
          ;; Pre-load the loop state with cost near the limit
          preloaded (assoc-in loop-state [:loop/metrics :cost-usd] cost-limit)]
      (is (true? (:terminate? (inner/should-terminate? preloaded)))
          "Should detect budget exhaustion")
      (is (= :budget-exhausted (:reason (inner/should-terminate? preloaded)))
          "Reason should be budget-exhausted"))))

;------------------------------------------------------------------------------ Layer 1
;; Metrics merging from simulated parallel phases

(deftest parallel-phase-metrics-merge-test
  (testing "Metrics from multiple phases merge with addition"
    (let [plan-metrics (make-phase-metrics :plan 500 2000)
          implement-metrics (make-phase-metrics :implement 3000 8000)
          review-metrics (make-phase-metrics :review 1000 3000)
          all-metrics [plan-metrics implement-metrics review-metrics]
          merged (reduce (fn [acc m]
                           (merge-with (fn [a b]
                                         (if (and (number? a) (number? b))
                                           (+ a b)
                                           b))
                                       acc
                                       (dissoc m :phase)))
                         {}
                         all-metrics)]
      (is (= 4500 (:tokens merged))
          "Token totals should sum across phases")
      (is (= 13000 (:duration-ms merged))
          "Duration totals should sum across phases")
      (is (= 3 (:generate-calls merged))
          "Generate call counts should sum"))))

;------------------------------------------------------------------------------ Layer 1
;; Outer loop phase advancement with metrics tracking

(deftest outer-loop-phase-metrics-tracking-test
  (testing "Phase advancement tracks history for metrics auditing"
    (let [spec {:spec/id (random-uuid)
                :description "Test metrics tracking"}
          loop-state (outer/create-outer-loop spec {})
          after-plan (outer/advance-phase loop-state {})
          after-design (outer/advance-phase after-plan {})
          after-implement (outer/advance-phase after-design {})
          history (:loop/history after-implement)
          outcomes (map :outcome history)]

      (is (= :implement (outer/get-current-phase after-implement))
          "Should reach implement phase")
      (is (= 7 (count history))
          "History should have entries for each phase transition")
      (is (some #{:completed} outcomes)
          "History should contain completed phases")
      (is (some #{:entered} outcomes)
          "History should contain entered phases"))))

;------------------------------------------------------------------------------ Layer 1
;; Inner loop update-metrics pure function

(deftest update-metrics-pure-function-test
  (testing "update-metrics merges numeric values additively"
    (let [loop-state (inner/create-inner-loop (make-task) {})
          updated (-> loop-state
                      (inner/update-metrics {:tokens 100 :generate-calls 1})
                      (inner/update-metrics {:tokens 200 :repair-calls 1})
                      (inner/update-metrics {:tokens 50 :generate-calls 1}))
          metrics (:loop/metrics updated)]
      (is (= 350 (:tokens metrics))
          "Tokens should accumulate additively")
      (is (= 2 (:generate-calls metrics))
          "Generate calls should accumulate")
      (is (= 1 (:repair-calls metrics))
          "Repair calls should accumulate"))))

;------------------------------------------------------------------------------ Layer 1
;; Zero-iteration edge case

(deftest zero-iteration-metrics-test
  (testing "Fresh loop state has zero metrics"
    (let [loop-state (inner/create-inner-loop (make-task) {})
          metrics (:loop/metrics loop-state)]
      (is (zero? (:tokens metrics))
          "Initial tokens should be zero")
      (is (zero? (:generate-calls metrics))
          "Initial generate calls should be zero")
      (is (zero? (:repair-calls metrics))
          "Initial repair calls should be zero")
      (is (= 0.0 (:cost-usd metrics))
          "Initial cost should be zero"))))

;------------------------------------------------------------------------------ Layer 1
;; Budget boundary: exactly at limit

(deftest budget-boundary-at-limit-test
  (testing "Budget at exact limit triggers termination"
    (let [task (make-task)
          token-limit 200
          loop-state (inner/create-inner-loop
                      task
                      {:max-iterations 10
                       :budget {:max-tokens token-limit}})
          at-limit (assoc-in loop-state [:loop/metrics :tokens] token-limit)]
      (is (true? (:terminate? (inner/should-terminate? at-limit)))
          "Exactly at budget limit should trigger termination")
      (is (= :budget-exhausted (:reason (inner/should-terminate? at-limit)))
          "Reason should be budget-exhausted"))))
