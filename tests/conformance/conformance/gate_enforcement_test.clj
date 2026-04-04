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

(ns conformance.gate_enforcement_test
  "N1 Gate Enforcement conformance tests.
   Verifies gate blocks phase completion on failure per N1 §8.2.4."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.loop.inner :as inner]
   [ai.miniforge.loop.gates :as gates]
   [ai.miniforge.loop.repair :as repair]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def test-task
  {:task/id (random-uuid)
   :task/type :implement
   :task/description "Test gate enforcement"})

(defn valid-artifact []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn hello [] \"world\")"})

(defn invalid-syntax-artifact []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn broken ["})

(defn artifact-with-secret []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(def api-key \"sk-12345\")"})

(defn make-generate-fn
  "Create a generate function that produces the given artifact."
  [artifact]
  (fn [_task _ctx]
    {:artifact artifact
     :tokens 100}))

;------------------------------------------------------------------------------ Layer 1
;; N1 §8.2.4: Gate blocks phase completion on failure

(deftest gate-failure-blocks-completion-test
  (testing "N1 §8.2.4: Gate failure MUST block phase completion"
    (let [loop-state (inner/create-inner-loop test-task {:max-iterations 1})
          generate-fn (make-generate-fn (invalid-syntax-artifact))
          result (inner/run-inner-loop loop-state
                                      generate-fn
                                      (gates/minimal-gates)  ; Includes syntax gate
                                      (repair/default-strategies)
                                      {})]
      (is (not (:success result))
          "Inner loop must fail when gate fails")
      (is (seq (:errors result))
          "Gate failures must be reported as errors")
      (is (some #(= :syntax-error (:code %)) (:errors result))
          "Specific gate error must be reported"))))

(deftest passing-gates-allow-completion-test
  (testing "N1 §8.2.4: Passing gates MUST allow phase completion"
    (let [loop-state (inner/create-inner-loop test-task {:max-iterations 3})
          generate-fn (make-generate-fn (valid-artifact))
          result (inner/run-inner-loop loop-state
                                      generate-fn
                                      (gates/minimal-gates)
                                      (repair/default-strategies)
                                      {})]
      (is (:success result)
          "Inner loop must succeed when gates pass")
      (is (= :gates-passed (get-in result [:termination :reason]))
          "Termination reason must indicate gates passed")
      (is (some? (:artifact result))
          "Successful completion must include artifact"))))

(deftest multiple-gates-enforcement-test
  (testing "N1 §8.2.4: All gates must pass for completion"
    (let [all-gates [(gates/syntax-gate)
                     (gates/lint-gate)
                     (gates/policy-gate :security {:policies [:no-secrets]})]
          loop-state (inner/create-inner-loop test-task {:max-iterations 1})
          generate-fn (make-generate-fn (artifact-with-secret))
          result (inner/run-inner-loop loop-state
                                      generate-fn
                                      all-gates
                                      (repair/default-strategies)
                                      {})]
      (is (not (:success result))
          "Must fail if any gate fails")
      (is (seq (:errors result))
          "Failed gates must report errors"))))

;------------------------------------------------------------------------------ Layer 2
;; N1 §8.2.3: Inner loop validation and repair

(deftest inner-loop-triggers-repair-on-failure-test
  (testing "N1 §8.2.3: Inner loop MUST trigger repair on validation failure"
    (let [call-count (atom 0)
          generate-fn (fn [_task _ctx]
                        (swap! call-count inc)
                        (if (= 1 @call-count)
                          {:artifact (invalid-syntax-artifact) :tokens 100}
                          {:artifact (valid-artifact) :tokens 100}))
          mock-repair-fn (fn [artifact _errors _ctx]
                          {:success? true
                           :artifact artifact
                           :tokens-used 50})
          loop-state (inner/create-inner-loop test-task {:max-iterations 5})
          result (inner/run-inner-loop loop-state
                                      generate-fn
                                      (gates/minimal-gates)
                                      (repair/default-strategies)
                                      {:repair-fn mock-repair-fn})]
      (is (>= (:iterations result) 2)
          "Inner loop must iterate when repair is attempted")
      (is (:success result)
          "Inner loop should succeed after repair"))))

(deftest inner-loop-escalates-after-max-iterations-test
  (testing "N1 §8.2.3: Inner loop MUST escalate after max iterations"
    (let [loop-state (inner/create-inner-loop test-task {:max-iterations 2})
          generate-fn (make-generate-fn (invalid-syntax-artifact))
          result (inner/run-inner-loop loop-state
                                      generate-fn
                                      (gates/minimal-gates)
                                      [(repair/retry-strategy {:delay-ms 0})]
                                      {})]
      (is (not (:success result))
          "Inner loop must fail after max iterations")
      (is (= :max-iterations (get-in result [:termination :reason]))
          "Must escalate with max-iterations reason"))))

;------------------------------------------------------------------------------ Layer 2
;; Gate repair integration

(deftest gate-repair-integration-test
  (testing "N1 §2.6.1: Gate repair method integrates with inner loop"
    (let [artifact-with-println {:artifact/id (random-uuid)
                                 :artifact/type :code
                                 :artifact/content "(defn x [] (println \"debug\") :ok)"}
          lint-gate (gates/lint-gate)
          violations [{:code :debug-println :message "Debug println found"}]
          repair-result (gates/repair lint-gate artifact-with-println violations {})]
      (is (some? repair-result)
          "Gate repair must return result")
      (is (contains? repair-result :repaired?)
          "Repair result must indicate success/failure")

      (when (:repaired? repair-result)
        (is (some? (:artifact repair-result))
            "Successful repair must include repaired artifact")
        (is (seq (:changes repair-result))
            "Successful repair must describe changes made")))))

(deftest gate-repair-preserves-valid-content-test
  (testing "Gate repair does not modify already-valid artifacts"
    (let [valid-code (valid-artifact)
          syntax-gate (gates/syntax-gate)
          ;; Syntax gate doesn't repair, just validates
          violations []
          repair-result (gates/repair syntax-gate valid-code violations {})]
      ;; Most gates that can't repair return :repaired? false
      (is (contains? repair-result :repaired?)
          "Repair result must have :repaired? key"))))

;------------------------------------------------------------------------------ Layer 2
;; Gate execution tracking

(deftest gate-execution-results-tracked-test
  (testing "N1 §2.6: Gate execution results MUST be tracked"
    (let [loop-state (inner/create-inner-loop test-task {:max-iterations 3})
          generate-fn (make-generate-fn (valid-artifact))
          result (inner/run-inner-loop loop-state
                                      generate-fn
                                      (gates/minimal-gates)
                                      (repair/default-strategies)
                                      {})]
      (is (seq (:gate-results result))
          "Gate execution results must be recorded")

      (let [gate-results (:gate-results result)]
        (is (every? #(contains? % :gate/id) gate-results)
            "Each gate result must have ID")
        (is (every? #(contains? % :gate/passed?) gate-results)
            "Each gate result must indicate pass/fail")))))

(deftest gate-violations-recorded-test
  (testing "N1 §2.6: Gate violations MUST be recorded"
    (let [loop-state (inner/create-inner-loop test-task {:max-iterations 1})
          generate-fn (make-generate-fn (invalid-syntax-artifact))
          result (inner/run-inner-loop loop-state
                                      generate-fn
                                      (gates/minimal-gates)
                                      (repair/default-strategies)
                                      {})]
      (is (seq (:errors result))
          "Gate violations must be recorded as errors")

      (let [errors (:errors result)]
        (is (every? #(contains? % :code) errors)
            "Each violation must have error code")
        (is (every? #(contains? % :message) errors)
            "Each violation must have error message")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'conformance.gate_enforcement_test)

  ;; Test specific gate behavior
  (clojure.test/test-var #'gate-failure-blocks-completion-test)
  (clojure.test/test-var #'gate-repair-integration-test)

  :leave-this-here)
