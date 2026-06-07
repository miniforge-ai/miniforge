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
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [ai.miniforge.phase-software-factory.review :as review]
   [ai.miniforge.phase-software-factory.phase-config :as phase-config]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers and var-access

(def ^:private schema
  "Direct var access to the private schema for white-box validation tests."
  #'ai.miniforge.phase-software-factory.review/ReviewConvergenceConfigSchema)

(defn- valid?
  "Returns true when the candidate map satisfies the convergence schema."
  [candidate]
  (m/validate @schema candidate))

;------------------------------------------------------------------------------ Layer 1
;; Named-constant tests

(deftest test-default-warning-churn-policy-value
  (testing "default policy is :accept-with-warnings"
    (is (= :accept-with-warnings review/default-warning-churn-policy))))

(deftest test-default-max-warning-only-cycles-value
  (testing "default max cycles is 2"
    (is (= 2 review/default-max-warning-only-cycles))))

;------------------------------------------------------------------------------ Layer 2
;; Schema validation tests

(deftest test-schema-accepts-valid-accept-with-warnings
  (testing "given :accept-with-warnings policy and positive cycle count → valid"
    (is (valid? {:review/warning-churn-policy  :accept-with-warnings
                 :review/max-warning-only-cycles 2}))))

(deftest test-schema-accepts-valid-needs-decomposition
  (testing "given :needs-decomposition policy and positive cycle count → valid"
    (is (valid? {:review/warning-churn-policy  :needs-decomposition
                 :review/max-warning-only-cycles 1}))))

(deftest test-schema-rejects-unknown-policy
  (testing "given an unknown policy keyword → invalid"
    (is (not (valid? {:review/warning-churn-policy  :unknown-policy
                      :review/max-warning-only-cycles 2})))))

(deftest test-schema-rejects-zero-cycle-count
  (testing "given max-warning-only-cycles = 0 → invalid (min is 1)"
    (is (not (valid? {:review/warning-churn-policy  :accept-with-warnings
                      :review/max-warning-only-cycles 0})))))

(deftest test-schema-rejects-negative-cycle-count
  (testing "given max-warning-only-cycles < 0 → invalid"
    (is (not (valid? {:review/warning-churn-policy  :accept-with-warnings
                      :review/max-warning-only-cycles -1})))))

(deftest test-schema-rejects-string-policy
  (testing "given a string instead of a keyword for the policy → invalid"
    (is (not (valid? {:review/warning-churn-policy  "accept-with-warnings"
                      :review/max-warning-only-cycles 2})))))

;; EDN config round-trip

(deftest test-defaults-edn-provides-convergence-keys
  (testing "defaults.edn :review map includes both convergence keys"
    (let [cfg (phase-config/defaults-for :review)]
      (is (contains? cfg :review/warning-churn-policy))
      (is (contains? cfg :review/max-warning-only-cycles)))))

(deftest test-defaults-edn-convergence-keys-are-valid
  (testing "given convergence keys from defaults.edn → schema validates"
    (let [cfg (phase-config/defaults-for :review)
          candidate (select-keys cfg [:review/warning-churn-policy
                                      :review/max-warning-only-cycles])]
      (is (valid? candidate)))))

(deftest test-defaults-edn-policy-matches-constant
  (testing "defaults.edn :review/warning-churn-policy matches the fallback constant"
    (let [cfg (phase-config/defaults-for :review)]
      (is (= review/default-warning-churn-policy
             (:review/warning-churn-policy cfg))))))

(deftest test-defaults-edn-cycle-count-matches-constant
  (testing "defaults.edn :review/max-warning-only-cycles matches the fallback constant"
    (let [cfg (phase-config/defaults-for :review)]
      (is (= review/default-max-warning-only-cycles
             (:review/max-warning-only-cycles cfg))))))

(comment
  ;; REPL smoke-test
  (valid? {:review/warning-churn-policy :accept-with-warnings
           :review/max-warning-only-cycles 2})   ;; => true
  (valid? {:review/warning-churn-policy :bad :review/max-warning-only-cycles 0}) ;; => false
  (phase-config/defaults-for :review))
