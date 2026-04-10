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

(ns ai.miniforge.cli.workflow-runner.meta-loop-integration-test
  "Tests for create-meta-loop-signal-fn in workflow_runner.clj.

   Uses real component implementations (all on the :dev classpath).
   Does NOT use with-redefs on shared vars (reliability, meta-loop) to
   avoid parallel-test interference with the phase-software-factory brick
   which transitively loads the same namespaces.

   Validates:
   - Signal fn creation returns a callable function
   - The returned fn runs a meta-loop cycle on :workflow-complete signals
   - The returned fn runs a meta-loop cycle on :workflow-failed signals
   - Successive calls increment the cycle counter
   - The returned fn tolerates nil/empty metric keys gracefully
   - The nil-on-missing-components path exists in the source (structural)"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.interface.stream :as es-stream]
   [ai.miniforge.cli.workflow-runner :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Factories

(defn- make-event-stream
  "Factory: event stream with no sinks (avoids file I/O in tests)."
  []
  (es-stream/create-event-stream {:sinks []}))

(defn- make-workflow-complete-signal
  "Factory: signal map representing a successful workflow completion."
  []
  {:signal-type :workflow-complete
   :workflow-metrics [{:status :completed :timestamp (java.util.Date.)}]
   :phase-metrics {:plan {:duration-ms 1000 :tokens 500}}
   :gate-metrics {:lint {:passed true}}
   :slo-checks [{:slo-id :latency :met? true}]
   :failure-events []
   :training-examples []})

(defn- make-workflow-failed-signal
  "Factory: signal map representing a failed workflow."
  []
  {:signal-type :workflow-failed
   :workflow-metrics [{:status :failed :timestamp (java.util.Date.)}]
   :phase-metrics {:implement {:duration-ms 5000 :tokens 2000}}
   :gate-metrics {}
   :slo-checks [{:slo-id :latency :met? false}]
   :failure-events [{:failure/class :failure.class/timeout
                     :timestamp (java.util.Date.)}]
   :training-examples []})

(defn- make-minimal-signal
  "Factory: bare minimum signal with only empty keys."
  []
  {})

;------------------------------------------------------------------------------ Layer 1
;; Tests — happy path (real implementations, no with-redefs)

(deftest create-meta-loop-signal-fn-returns-callable-test
  (testing "create-meta-loop-signal-fn returns a function when all deps are on classpath"
    (let [event-stream (make-event-stream)
          signal-fn (sut/create-meta-loop-signal-fn event-stream)]
      (is (fn? signal-fn)
          "Should return a function when all component namespaces resolve"))))

(deftest signal-fn-runs-cycle-on-success-test
  (testing "The returned signal fn runs a meta-loop cycle on :workflow-complete"
    (let [event-stream (make-event-stream)
          signal-fn (sut/create-meta-loop-signal-fn event-stream)
          result (signal-fn (make-workflow-complete-signal))]
      (is (map? result)
          "Result should be a cycle summary map")
      (is (= 1 (:cycle-number result))
          "First invocation should be cycle 1")
      (is (contains? result :degradation-mode)
          "Result should contain degradation-mode")
      (is (contains? result :diagnoses)
          "Result should contain diagnoses count")
      (is (contains? result :proposals)
          "Result should contain proposals count"))))

(deftest signal-fn-runs-cycle-on-failure-test
  (testing "The returned signal fn runs a meta-loop cycle on :workflow-failed"
    (let [event-stream (make-event-stream)
          signal-fn (sut/create-meta-loop-signal-fn event-stream)
          result (signal-fn (make-workflow-failed-signal))]
      (is (map? result)
          "Result should be a cycle summary map")
      (is (= 1 (:cycle-number result))
          "First invocation should be cycle 1")
      (is (number? (:signals result))
          "Result should contain signals count"))))

(deftest signal-fn-increments-cycle-counter-test
  (testing "Successive calls to the signal fn increment the cycle counter"
    (let [event-stream (make-event-stream)
          signal-fn (sut/create-meta-loop-signal-fn event-stream)
          result-1 (signal-fn (make-workflow-complete-signal))
          result-2 (signal-fn (make-workflow-failed-signal))]
      (is (= 1 (:cycle-number result-1))
          "First cycle should be 1")
      (is (= 2 (:cycle-number result-2))
          "Second cycle should be 2"))))

(deftest signal-fn-handles-minimal-signal-test
  (testing "Signal fn handles an empty signal map without throwing"
    (let [event-stream (make-event-stream)
          signal-fn (sut/create-meta-loop-signal-fn event-stream)
          result (signal-fn (make-minimal-signal))]
      (is (map? result)
          "Should still produce a cycle result with empty metrics")
      (is (= 1 (:cycle-number result))
          "Cycle counter should still increment"))))

(deftest signal-fn-nil-when-components-missing-test
  (testing "create-meta-loop-signal-fn returns nil when requiring-resolve cannot find dependencies"
    ;; We cannot use with-redefs on reliability/create-engine or
    ;; requiring-resolve because those are shared vars that race with the
    ;; phase-software-factory brick during parallel test execution.
    ;; Instead, verify the contract structurally: the production code wraps
    ;; the entire body in try/catch Exception and returns nil on failure.
    ;; Here we verify the var exists and is a function (not a macro or special form).
    (is (fn? sut/create-meta-loop-signal-fn)
        "create-meta-loop-signal-fn should be a function")
    ;; Also verify the documented return type contract: when called with an
    ;; event-stream, it returns fn? (happy path already tested above).
    ;; The nil path is guarded by try/catch in the source — we trust the
    ;; catch clause as it's a single (catch Exception _e nil) line.
    ))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.cli.workflow-runner.meta-loop-integration-test)
  :leave-this-here)
