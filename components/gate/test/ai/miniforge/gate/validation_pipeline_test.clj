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

(ns ai.miniforge.gate.validation-pipeline-test
  "Integration test for gate validation pipeline with repair loop.

   Tests the full gate chain: artifact flow through multiple gates,
   failure-triggered repair, escalation on repair exhaustion, and
   metrics accumulation through repair cycles.

   Mock gates and repair strategies replace real LLM calls and external tools."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.loop.inner :as inner]
   [ai.miniforge.loop.gates :as gates]
   [ai.miniforge.loop.repair :as repair]))

;------------------------------------------------------------------------------ Layer 0
;; Factory functions

(def default-max-iterations 5)
(def repair-token-cost 50)

(defn make-task
  "Create a test task with a random ID."
  []
  {:task/id (random-uuid)
   :task/type :implement})

(defn make-valid-artifact
  "Create a syntactically valid code artifact."
  []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn greet [name] (str \"Hello, \" name))"})

(defn make-invalid-syntax-artifact
  "Create an artifact with broken syntax."
  []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn broken ["})

(defn make-secret-bearing-artifact
  "Create an artifact containing a hardcoded secret."
  []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(def api-key = \"sk-abcdefghijklmnopqrstuvwxyz1234567890\")"})

(defn make-clean-artifact
  "Create an artifact that passes all gates including policy."
  []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn add\n  \"Add two numbers.\"\n  [a b]\n  (+ a b))"})

(defn make-generate-fn
  "Create a generate function that returns the given artifact."
  [artifact & {:keys [tokens] :or {tokens 100}}]
  (fn [_task _ctx]
    {:artifact artifact
     :tokens tokens}))

(defn make-alternating-generate-fn
  "Create a generate function that alternates between two artifacts.
   First call returns artifact-a, second returns artifact-b, then cycles."
  [artifact-a artifact-b]
  (let [calls (atom 0)]
    (fn [_task _ctx]
      (swap! calls inc)
      (if (odd? @calls)
        {:artifact artifact-a :tokens 100}
        {:artifact artifact-b :tokens 100}))))

(defn make-repair-fn
  "Create a mock repair function that replaces artifact content."
  [fixed-content]
  (fn [artifact _errors _ctx]
    {:success? true
     :artifact (assoc artifact :artifact/content fixed-content)
     :tokens-used repair-token-cost}))

(defn make-failing-repair-fn
  "Create a repair function that always fails."
  []
  (fn [_artifact _errors _ctx]
    {:success? false
     :errors [{:code :repair-failed :message "Could not fix"}]
     :tokens-used repair-token-cost}))

;------------------------------------------------------------------------------ Layer 1
;; Gate chain: happy path

(deftest gate-chain-all-pass-test
  (testing "Artifact flows through all gates when valid"
    (let [artifact (make-clean-artifact)
          gate-set (gates/default-gates)
          result (gates/run-gates gate-set artifact {})]
      (is (true? (:passed? result))
          "All default gates should pass for clean artifact")
      (is (empty? (:failed-gates result))
          "No gates should be listed as failed")
      (is (empty? (:errors result))
          "No errors should be reported")
      (is (= (count gate-set) (count (:results result)))
          "Every gate should produce a result"))))

(deftest gate-chain-syntax-failure-test
  (testing "Syntax failure stops propagation with fail-fast"
    (let [artifact (make-invalid-syntax-artifact)
          gate-set (gates/default-gates)
          result (gates/run-gates gate-set artifact {} :fail-fast? true)]
      (is (false? (:passed? result))
          "Pipeline should fail on broken syntax")
      (is (= 1 (count (:results result)))
          "Only one gate should have run in fail-fast mode")
      (is (some #{:syntax-check} (:failed-gates result))
          "Syntax gate should be in the failed list"))))

(deftest gate-chain-policy-failure-test
  (testing "Secret-bearing artifact fails policy gate"
    (let [artifact (make-secret-bearing-artifact)
          gate-set [(gates/syntax-gate)
                    (gates/policy-gate :policy {:policies [:no-secrets]})]
          result (gates/run-gates gate-set artifact {})]
      (is (false? (:passed? result))
          "Pipeline should fail on hardcoded secret")
      (is (some #{:policy} (:failed-gates result))
          "Policy gate should be in the failed list"))))

;------------------------------------------------------------------------------ Layer 1
;; Inner loop integration: repair triggers correctly

(deftest inner-loop-repair-cycle-test
  (testing "Gate failure triggers repair, repaired artifact passes"
    (let [task (make-task)
          fixed-content "(defn fixed [] :ok)"
          loop-state (inner/create-inner-loop task {:max-iterations default-max-iterations})
          generate-fn (make-alternating-generate-fn
                       (make-invalid-syntax-artifact)
                       (assoc (make-valid-artifact) :artifact/content fixed-content))
          repair-fn (make-repair-fn fixed-content)
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       (repair/default-strategies)
                                       {:repair-fn repair-fn})]
      (is (true? (:success result))
          "Loop should succeed after repair")
      (is (= 2 (:iterations result))
          "Should take exactly two iterations: fail then succeed")
      (is (pos? (get-in result [:metrics :tokens]))
          "Tokens should have accumulated"))))

(deftest inner-loop-escalation-on-exhausted-repairs-test
  (testing "Exhausted repair budget escalates"
    (let [task (make-task)
          max-iter 2
          loop-state (inner/create-inner-loop task {:max-iterations max-iter})
          generate-fn (make-generate-fn (make-invalid-syntax-artifact))
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       [(repair/retry-strategy {:delay-ms 0})]
                                       {})]
      (is (false? (:success result))
          "Loop should not succeed when all repairs fail")
      (is (= :max-iterations (get-in result [:termination :reason]))
          "Termination reason should be max-iterations"))))

;------------------------------------------------------------------------------ Layer 1
;; Budget enforcement

(deftest budget-token-enforcement-test
  (testing "Token budget exhaustion terminates loop"
    (let [task (make-task)
          token-budget 150
          loop-state (inner/create-inner-loop
                      task
                      {:max-iterations 10
                       :budget {:max-tokens token-budget}})
          ;; Each call costs 100 tokens; after 2 calls (200 tokens) budget is exceeded
          generate-fn (make-generate-fn (make-invalid-syntax-artifact) :tokens 100)
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       [(repair/retry-strategy {:delay-ms 0})]
                                       {})]
      (is (false? (:success result))
          "Loop should terminate when token budget is exhausted")
      (is (= :budget-exhausted (get-in result [:termination :reason]))
          "Termination reason should be budget-exhausted"))))

;------------------------------------------------------------------------------ Layer 1
;; Metrics accumulation through repair cycles

(deftest metrics-accumulation-through-repairs-test
  (testing "Metrics accumulate tokens and call counts across iterations"
    (let [task (make-task)
          call-count (atom 0)
          generate-fn (fn [_task _ctx]
                        (swap! call-count inc)
                        (if (<= @call-count 2)
                          {:artifact (make-invalid-syntax-artifact) :tokens 100}
                          {:artifact (make-valid-artifact) :tokens 100}))
          repair-fn (make-repair-fn "(defn hello [] :ok)")
          loop-state (inner/create-inner-loop task {:max-iterations default-max-iterations})
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       (repair/default-strategies)
                                       {:repair-fn repair-fn})]
      (is (true? (:success result))
          "Loop should eventually succeed")
      (let [metrics (:metrics result)]
        (is (>= (:tokens metrics) 300)
            "Accumulated tokens should reflect all generate calls")
        (is (>= (:generate-calls metrics) 3)
            "Generate call count should reflect all iterations")
        (is (pos? (:repair-calls metrics))
            "Repair call count should be positive")))))

;------------------------------------------------------------------------------ Layer 1
;; Custom gate integration

(deftest custom-gate-in-pipeline-test
  (testing "Custom gate participates in the validation pipeline"
    (let [max-length 50
          short-artifact {:artifact/id (random-uuid)
                          :artifact/type :code
                          :artifact/content "(defn x [] 1)"}
          long-artifact {:artifact/id (random-uuid)
                         :artifact/type :code
                         :artifact/content (apply str (repeat 100 "(defn x [] 1) "))}
          length-gate (gates/custom-gate
                       :max-length
                       (fn [artifact _ctx]
                         (let [len (count (get artifact :artifact/content ""))]
                           (if (> len max-length)
                             (gates/fail-result :max-length :custom
                                                [(gates/make-error :too-long "Content too long")])
                             (gates/pass-result :max-length :custom)))))
          pipeline [(gates/syntax-gate) length-gate]]

      (is (true? (:passed? (gates/run-gates pipeline short-artifact {})))
          "Short artifact should pass custom length gate")
      (is (false? (:passed? (gates/run-gates pipeline long-artifact {})))
          "Long artifact should fail custom length gate"))))

;------------------------------------------------------------------------------ Layer 1
;; Fail-fast vs run-all comparison

(deftest fail-fast-vs-run-all-test
  (testing "fail-fast stops early; run-all reports all failures"
    (let [artifact (make-invalid-syntax-artifact)
          gate-set (gates/strict-gates)
          fast-result (gates/run-gates gate-set artifact {} :fail-fast? true)
          full-result (gates/run-gates gate-set artifact {} :fail-fast? false)]
      (is (false? (:passed? fast-result))
          "Fail-fast should detect failure")
      (is (false? (:passed? full-result))
          "Full run should also detect failure")
      (is (<= (count (:results fast-result))
              (count (:results full-result)))
          "Fail-fast should run fewer or equal gates compared to full run"))))
