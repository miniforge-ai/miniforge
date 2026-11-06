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
(ns ai.miniforge.phase-software-factory.verify-gate-validation-test
  "Verify's real :enter result through its configured gates, by the two
   calls the workflow's gate step makes: check-gates on the phase's
   canonical :output, then gates->envelope, which denies a nil output as
   :reason/missing-artifact."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.phase-software-factory.verify :as verify]
   [ai.miniforge.phase-software-factory.verify.result :as verify-result]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private run-tests-var
  #'verify/run-tests!)

(def ^{:stratum 0} ^:private passing-run
  "`run-tests!`'s result for a suite whose 5 tests all pass, built with the
   runner's own constructor so the shape matches production."
  (verify-result/summary true 5 5 0 0 "5 tests, 5 assertions, 0 failures"))

(def ^{:stratum 0} ^:private failing-run
  "`run-tests!`'s result for a suite with 2 failing tests of 3."
  (verify-result/summary false 3 3 2 0 "3 tests, 3 assertions, 2 failures"))

(defn- ^{:stratum 0} complete-with-no-findings
  [& _]
  {:success true :content "[]"})

(defn- ^{:stratum 0} analyze-with-no-violations
  [_client _complete _repo _rule]
  {:status :completed :violations []})

(defn- ^{:stratum 0} gate-decision
  [ctx]
  (let [artifact (response/phase-output (:phase ctx))
        checked  (gate/check-gates (:gates verify/default-config) artifact ctx)]
    (gate/gates->envelope checked (nil? artifact))))

(defn- ^{:stratum 0} reason-codes
  [envelope]
  (set (map :reason/code (:envelope/reasons envelope))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-ctx
  []
  ;; A judge that finds nothing: without semantic wiring, :policy-verify's
  ;; acting rules fail closed and hide the decision under test.
  {:llm-client               ::stub-client
   :complete-fn              complete-with-no-findings
   :semantic-analyze-fn      analyze-with-no-violations
   :execution/id             (random-uuid)
   :execution/environment-id (random-uuid)
   :execution/worktree-path  "/tmp/verify-gate-validation"
   :execution/input          {:title "Verify gates" :description "test"}
   :execution/metrics        {:tokens 0 :duration-ms 0}
   :phase-config             {:phase :verify}})

(defn- ^{:stratum 1} entered-verify
  [ctx test-results]
  (with-redefs-fn
    {run-tests-var (fn [_ & _opts] test-results)}
    (fn [] (verify/enter-verify ctx))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} passing-tests-are-allowed-test
  (testing "given passing tests → verify's own gates allow it"
    (let [ctx      (entered-verify (verify-ctx) passing-run)
          envelope (gate-decision ctx)]
      (is (= {:verify/tests-passed? true}
             (response/phase-output (:phase ctx)))
          "verify's canonical output is its test verdict")
      (is (not (contains? (reason-codes envelope) :reason/missing-artifact)))
      (is (gate/decision-allowed? envelope)))))

(deftest ^{:stratum 2} failing-tests-are-denied-for-the-failure-test
  (testing "given failing tests → denied for the failures, not for a missing
            artifact"
    (let [ctx      (entered-verify (verify-ctx) failing-run)
          envelope (gate-decision ctx)]
      (is (= {:verify/tests-passed? false}
             (response/phase-output (:phase ctx))))
      (is (not (contains? (reason-codes envelope) :reason/missing-artifact)))
      (is (not (gate/decision-allowed? envelope))))))
