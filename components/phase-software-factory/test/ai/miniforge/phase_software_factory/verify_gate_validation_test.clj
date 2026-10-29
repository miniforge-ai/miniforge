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
  "Verify's real :enter result through its configured gates.

   The workflow's gate step hands gates the phase's canonical :output and
   mints the decision envelope with :reason/missing-artifact when that
   output is nil. Verify used to return no :output, so every verify was
   denied, passing tests included. These tests make the same two calls the
   workflow makes (check-gates on the canonical output, then
   gates->envelope) through the public gate and response interfaces."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.phase-software-factory.verify :as verify]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private run-tests-var
  #'verify/run-tests!)

(def ^{:stratum 0} ^:private judge-finding-nothing
  "Semantic wiring whose judge reports no violations. Without wiring,
   :policy-verify's acting semantic rules fail closed, which would hide the
   decision under test."
  {:llm-client          ::stub-client
   :complete-fn         (fn [& _] {:success true :content "[]"})
   :semantic-analyze-fn (fn [_client _complete _repo _rule]
                          {:status :completed :violations []})})

(defn- ^{:stratum 0} gate-decision
  "The envelope the workflow would mint for this entered verify ctx."
  [ctx]
  (let [artifact (response/phase-output (:phase ctx))
        checked  (gate/check-gates (:gates verify/default-config) artifact ctx)]
    (gate/gates->envelope checked (nil? artifact))))

(defn- ^{:stratum 0} reason-codes
  [envelope]
  (set (map :reason/code (:envelope/reasons envelope))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} entered-verify
  "Run the real enter-verify with the test runner returning `test-results`."
  [test-results]
  (with-redefs-fn
    {run-tests-var (fn [_ & _opts] test-results)}
    (fn []
      (verify/enter-verify
       (merge judge-finding-nothing
              {:execution/id (random-uuid)
               :execution/environment-id (random-uuid)
               :execution/worktree-path "/tmp/verify-gate-validation"
               :execution/input {:title "Verify gates" :description "test"}
               :execution/metrics {:tokens 0 :duration-ms 0}
               :phase-config {:phase :verify}})))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} passing-tests-are-allowed-test
  (testing "a verify whose tests pass is allowed by its own gates"
    (let [ctx      (entered-verify {:passed? true :test-count 5 :fail-count 0
                                    :error-count 0 :output "5 tests, 0 failures"})
          envelope (gate-decision ctx)]
      (is (= {:verify/tests-passed? true}
             (response/phase-output (:phase ctx)))
          "verify's canonical output is its test verdict")
      (is (not (contains? (reason-codes envelope) :reason/missing-artifact)))
      (is (gate/decision-allowed? envelope)))))

(deftest ^{:stratum 2} failing-tests-are-denied-for-the-failure-test
  (testing "a verify whose tests fail is denied for the failing tests, not
            for a missing artifact"
    (let [ctx      (entered-verify {:passed? false :test-count 3 :fail-count 2
                                    :error-count 0 :output "3 tests, 2 failures"})
          envelope (gate-decision ctx)]
      (is (= {:verify/tests-passed? false}
             (response/phase-output (:phase ctx))))
      (is (not (contains? (reason-codes envelope) :reason/missing-artifact)))
      (is (not (gate/decision-allowed? envelope))))))
