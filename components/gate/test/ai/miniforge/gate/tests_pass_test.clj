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
(ns ai.miniforge.gate.tests-pass-test
  "Tests for the :tests-pass gate.

   The production verify path (phase-software-factory `enter-verify`) leaves
   the phase :output nil and puts the test counts in [:result :metrics]. The
   gate must read them from the ctx it is handed — a nil artifact is not a
   pass (checkpoint f413dd80, 2026-09-04: every verify entry logged
   'Gate :tests-pass passed' with failing tests)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.gate.test :as gate-test]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} failures
  [{:kind :fail
    :test "recording-is-a-no-op-without-a-configured-codex"
    :location "gap_wiring_test.clj:106"
    :detail "expected: {:skipped 0}\n  actual: {:torn-lines 0}"}
   {:kind :error
    :test "reads-the-ledger"
    :location "ledger_test.clj:12"
    :detail "NullPointerException"}])

(defn- ^{:stratum 0} verify-ctx
  "Ctx as `apply-gate-validation` hands it to a gate: the entered ctx with
   the verify result at [:phase :result], shaped by `phase/success` /
   `phase/error` + `phase/test-metrics`."
  [result]
  {:phase {:name :verify :status :running :result result}})

(defn- ^{:stratum 0} verify-error-result
  [pass-count fail-count failures]
  {:status :error
   :environment-id "env-1"
   :summary (str fail-count " tests failed")
   :error {:message (str fail-count " tests failed")}
   :metrics {:tokens 0 :duration-ms 0
             :pass-count pass-count :fail-count fail-count
             :test-output "FAIL in ..." :failures failures}})

(deftest ^{:stratum 0} non-test-bearing-phase-result-falls-through-test
  (testing "a phase result whose :metrics has no :fail-count (implement: tokens,
            cost) is not a test result — legacy artifact source applies"
    (let [ctx {:phase {:result {:status :success
                                :metrics {:tokens 1200 :cost-usd 0.02}}}}]
      (is (true? (:passed? (gate-test/check-tests-pass
                            {:metadata {:test-results {:passed? true :test-count 3}}}
                            ctx))))
      (let [result (gate-test/check-tests-pass
                    {:metadata {:test-results {:passed? false :fail-count 1
                                               :failures [{:test "t"}]}}}
                    ctx)]
        (is (false? (:passed? result)))
        (is (= [{:test "t"}] (-> result :errors first :failures)))))))

(deftest ^{:stratum 0} no-results-anywhere-warns-test
  (testing "neither a test-bearing phase result nor artifact metadata: pass
            with a :no-tests warning (unchanged legacy behavior)"
    (let [result (gate-test/check-tests-pass nil {})]
      (is (true? (:passed? result)))
      (is (= :no-tests (-> result :warnings first :type))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} failing-tests-deny-test
  (testing "positive :fail-count in the phase result fails the gate even with
            a nil artifact (the production verify shape)"
    (let [result (gate-test/check-tests-pass nil (verify-ctx (verify-error-result 41 2 failures)))
          error  (first (:errors result))]
      (is (false? (:passed? result)))
      (is (= 2 (:fail-count result)))
      (is (= 41 (:pass-count result)))
      (is (= :tests-failed (:type error)))
      (is (= "2 tests failed" (:message error)))
      (is (= failures (:failures error))
          "verify's failing tests ride on the gate error for the implementer"))))

(deftest ^{:stratum 1} phase-error-without-failures-denies-test
  (testing "phase :status :error with zero failing tests (parse error, crashed or
            timed-out test command) fails — uncounted tests are not passed tests"
    (let [result (gate-test/check-tests-pass
                  nil
                  (verify-ctx {:status :error
                               :summary "bb test timed out after 300000 ms"
                               :error {:message "bb test timed out after 300000 ms"}
                               :metrics {:tokens 0 :duration-ms 0
                                         :pass-count 0 :fail-count 0
                                         :test-output "" :parse-error? true}}))
          error  (first (:errors result))]
      (is (false? (:passed? result)))
      (is (= :verify-error (:type error)))
      (is (= "bb test timed out after 300000 ms" (:message error)))
      (is (= [] (:failures error))))))

(deftest ^{:stratum 1} passing-tests-allow-test
  (testing "a successful verify result with zero failures passes and reports counts"
    (let [result (gate-test/check-tests-pass
                  nil
                  (verify-ctx {:status :success
                               :environment-id "env-1"
                               :summary "43 tests passed"
                               :metrics {:tokens 0 :duration-ms 0
                                         :pass-count 43 :fail-count 0
                                         :test-output "Ran 43 tests"}}))]
      (is (true? (:passed? result)))
      (is (= 43 (:pass-count result)))
      (is (empty? (:errors result)))
      (is (empty? (:warnings result))))))

(deftest ^{:stratum 1} phase-result-wins-over-artifact-metadata-test
  (testing "when both sources exist the phase result is canonical"
    (let [artifact {:metadata {:test-results {:passed? true :test-count 10}}}
          result   (gate-test/check-tests-pass artifact (verify-ctx (verify-error-result 9 1 failures)))]
      (is (false? (:passed? result))))))

(deftest ^{:stratum 1} check-gates-through-registry-test
  (testing "the registered :tests-pass gate denies a failing verify through
            check-gates, and the structured error survives for
            :phase/gate-failures"
    (let [gate-result (gate/check-gates [:tests-pass] nil (verify-ctx (verify-error-result 41 2 failures)))
          failed      (first (:failed-gates gate-result))]
      (is (false? (:passed? gate-result)))
      (is (= :tests-pass (:gate failed)))
      (is (= failures (-> failed :errors first :failures)))))
  (testing "the :test alias denies too, with the singular message for one failure"
    (let [gate-result (gate/check-gates [:test] nil (verify-ctx (verify-error-result 0 1 failures)))]
      (is (false? (:passed? gate-result)))
      (is (= "1 test failed" (-> gate-result :failed-gates first :errors first :message)))))
  (testing "and allows a green verify"
    (is (true? (:passed? (gate/check-gates [:tests-pass] nil
                                           (verify-ctx {:status :success
                                                        :metrics {:pass-count 5 :fail-count 0
                                                                  :test-output ""}})))))))
