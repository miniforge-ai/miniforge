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
(ns ai.miniforge.phase-software-factory.review-convergence-config-test
  "Tests for review convergence policy config — named constants, Malli schema,
   and defaults loaded from resources/config/phase/defaults.edn."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [ai.miniforge.phase-software-factory.messages :as messages]
   [ai.miniforge.phase-software-factory.review-convergence :as rconv]
   [ai.miniforge.phase-software-factory.phase-config :as phase-config]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers and var-access
(def ^{:stratum 0} ^:private schema
  "Direct var access to the private schema for white-box validation tests."
  #'ai.miniforge.phase-software-factory.review-convergence/ReviewConvergenceConfigSchema)

;; Named-constant tests
(deftest ^{:stratum 0} test-default-warning-churn-policy-value
  (testing "default policy is :accept-with-warnings"
    (is (= :accept-with-warnings rconv/default-warning-churn-policy))))

(deftest ^{:stratum 0} test-default-max-warning-only-cycles-value
  (testing "default max cycles is 2"
    (is (= 2 rconv/default-max-warning-only-cycles))))

;; EDN config round-trip
(deftest ^{:stratum 0} test-defaults-edn-provides-convergence-keys
  (testing "defaults.edn :review map includes both convergence keys"
    (let [cfg (phase-config/defaults-for :review)]
      (is (contains? cfg :review/warning-churn-policy))
      (is (contains? cfg :review/max-warning-only-cycles)))))

(deftest ^{:stratum 0} test-validate-convergence-config-result-returns-anomaly
  (testing "invalid convergence config is returned as a canonical anomaly"
    (let [result (rconv/validate-convergence-config-result
                  {:review/warning-churn-policy  :unknown-policy
                   :review/max-warning-only-cycles 0})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= :review-convergence/config
             (get-in result [:anomaly/data :anomaly/schema])))
      (is (some? (get-in result [:anomaly/data :anomaly/explain]))))))

(deftest ^{:stratum 0} test-validate-convergence-config-result-normalizes-defaults
  (testing "valid partial config returns the validated defaults merged in"
    (let [result (rconv/validate-convergence-config-result
                  {:review/max-warning-only-cycles 4
                   :review/other-key :kept})]
      (is (= :accept-with-warnings
             (:review/warning-churn-policy result)))
      (is (= 4 (:review/max-warning-only-cycles result)))
      (is (= :kept (:review/other-key result))))))

(deftest ^{:stratum 0} test-defaults-edn-policy-matches-constant
  (testing "defaults.edn :review/warning-churn-policy matches the fallback constant"
    (let [cfg (phase-config/defaults-for :review)]
      (is (= rconv/default-warning-churn-policy
             (:review/warning-churn-policy cfg))))))

(deftest ^{:stratum 0} test-defaults-edn-cycle-count-matches-constant
  (testing "defaults.edn :review/max-warning-only-cycles matches the fallback constant"
    (let [cfg (phase-config/defaults-for :review)]
      (is (= rconv/default-max-warning-only-cycles
             (:review/max-warning-only-cycles cfg))))))

;; System-locale message catalog tests
(deftest ^{:stratum 0} test-system-catalog-has-invalid-convergence-config-key
  (testing "system.edn exposes :review/invalid-convergence-config for startup errors"
    ;; create-translator falls back to `(name k)` for a missing key, so a
    ;; bare string/pos? check passes even when the catalog entry is absent.
    ;; Assert the resolved message DIFFERS from that fallback to actually
    ;; prove the resource is wired.
    (let [msg      (messages/ts :review/invalid-convergence-config)
          fallback (name :review/invalid-convergence-config)]
      (is (string? msg))
      (is (pos? (count msg)))
      (is (not= fallback msg)
          "message equals the (name k) fallback — catalog entry is missing"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} valid?
  "Returns true when the candidate map satisfies the convergence schema."
  [candidate]
  (m/validate @schema candidate))

;------------------------------------------------------------------------------ Layer 2

;; Schema validation tests
(deftest ^{:stratum 2} test-schema-accepts-valid-accept-with-warnings
  (testing "given :accept-with-warnings policy and positive cycle count → valid"
    (is (valid? {:review/warning-churn-policy  :accept-with-warnings
                 :review/max-warning-only-cycles 2}))))

(deftest ^{:stratum 2} test-schema-accepts-valid-needs-decomposition
  (testing "given :needs-decomposition policy and positive cycle count → valid"
    (is (valid? {:review/warning-churn-policy  :needs-decomposition
                 :review/max-warning-only-cycles 1}))))

(deftest ^{:stratum 2} test-schema-rejects-unknown-policy
  (testing "given an unknown policy keyword → invalid"
    (is (not (valid? {:review/warning-churn-policy  :unknown-policy
                      :review/max-warning-only-cycles 2})))))

(deftest ^{:stratum 2} test-schema-rejects-zero-cycle-count
  (testing "given max-warning-only-cycles = 0 → invalid (min is 1)"
    (is (not (valid? {:review/warning-churn-policy  :accept-with-warnings
                      :review/max-warning-only-cycles 0})))))

(deftest ^{:stratum 2} test-schema-rejects-negative-cycle-count
  (testing "given max-warning-only-cycles < 0 → invalid"
    (is (not (valid? {:review/warning-churn-policy  :accept-with-warnings
                      :review/max-warning-only-cycles -1})))))

(deftest ^{:stratum 2} test-schema-rejects-string-policy
  (testing "given a string instead of a keyword for the policy → invalid"
    (is (not (valid? {:review/warning-churn-policy  "accept-with-warnings"
                      :review/max-warning-only-cycles 2})))))

(deftest ^{:stratum 2} test-defaults-edn-convergence-keys-are-valid
  (testing "given convergence keys from defaults.edn → schema validates"
    (let [cfg (phase-config/defaults-for :review)
          candidate (select-keys cfg [:review/warning-churn-policy
                                      :review/max-warning-only-cycles])]
      (is (valid? candidate)))))

(comment
  ;; REPL smoke-test
  (valid? {:review/warning-churn-policy :accept-with-warnings
           :review/max-warning-only-cycles 2})   ;; => true
  (valid? {:review/warning-churn-policy :bad :review/max-warning-only-cycles 0}) ;; => false
  (phase-config/defaults-for :review)
  (messages/ts :review/invalid-convergence-config))
