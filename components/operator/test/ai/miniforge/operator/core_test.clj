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

(ns ai.miniforge.operator.core-test
  "Tests for the pure helpers in operator.core. The full SimpleOperator record
   has runtime dependencies on workflow / knowledge / logging components and is
   exercised by intervention_test.clj end-to-end via the public interface; this
   file targets the data-shaping helpers that have no such coupling."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.operator.core :as sut]
            [ai.miniforge.operator.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn- signal
  [type & {:as data}]
  (sut/create-signal type (or data {})))

(defn- failure-signal-for
  [phase]
  (signal :workflow-failed :phase phase))

(defn- rollback-signal-from-to
  [from-phase to-phase]
  (signal :phase-rollback :from-phase from-phase :to-phase to-phase))

(defn- repair-signal-for
  [error-type]
  (signal :repair-pattern :error-type error-type))

;------------------------------------------------------------------------------ Layer 1
;; default-config

(deftest default-config-shape-test
  (testing "default-config carries the documented retention/window/threshold keys"
    (let [c sut/default-config]
      (is (pos-int? (:signal-retention-ms c)))
      (is (pos-int? (:pattern-window-ms c)))
      (is (pos-int? (:min-pattern-occurrences c)))
      (is (number? (:auto-apply-threshold c)))
      (is (<= 0.0 (:auto-apply-threshold c) 1.0))
      (is (pos-int? (:shadow-period-ms c))))))

;------------------------------------------------------------------------------ Layer 1
;; create-signal

(deftest create-signal-required-fields-test
  (testing "create-signal stamps id, type, data, and timestamp"
    (let [s (sut/create-signal :workflow-failed {:phase :implement})]
      (is (uuid? (:signal/id s)))
      (is (= :workflow-failed (:signal/type s)))
      (is (= {:phase :implement} (:signal/data s)))
      (is (number? (:signal/timestamp s))))))

(deftest create-signal-uniqueness-test
  (testing "Each call gets a fresh id"
    (let [a (sut/create-signal :x {})
          b (sut/create-signal :x {})]
      (is (not= (:signal/id a) (:signal/id b))))))

;------------------------------------------------------------------------------ Layer 1
;; prune-old-signals

(deftest prune-old-signals-keeps-recent-test
  (testing "Recent signals (within retention) are kept"
    (let [now (System/currentTimeMillis)
          recent {:signal/id (random-uuid) :signal/type :x
                  :signal/data {} :signal/timestamp (- now 1000)}
          old    {:signal/id (random-uuid) :signal/type :x
                  :signal/data {} :signal/timestamp (- now 1000000)}
          kept   (sut/prune-old-signals [recent old] 5000)]
      (is (= [recent] kept)))))

(deftest prune-old-signals-keeps-empty-input-test
  (testing "Empty input returns empty (and is a vector for filterv)"
    (is (= [] (sut/prune-old-signals [] 1000)))
    (is (vector? (sut/prune-old-signals [] 1000)))))

;------------------------------------------------------------------------------ Layer 2
;; detect-repeated-failures

(deftest detect-repeated-failures-yields-pattern-test
  (testing "When a phase fails ≥ min-occurrences times, a :repeated-phase-failure pattern fires"
    (let [signals (vec (repeatedly 3 #(failure-signal-for :implement)))
          [pat] (sut/detect-repeated-failures signals 3)]
      (is (= :repeated-phase-failure (:pattern/type pat)))
      (is (= :implement (:pattern/phase pat)))
      (is (= 3 (:pattern/occurrences pat)))
      (is (number? (:pattern/confidence pat)))
      (is (= 3 (count (:pattern/signals pat)))))))

(deftest detect-repeated-failures-below-threshold-test
  (testing "Fewer than min-occurrences failures yield no pattern"
    (is (empty? (sut/detect-repeated-failures
                 [(failure-signal-for :implement)
                  (failure-signal-for :implement)]
                 3)))))

(deftest detect-repeated-failures-confidence-clamped-to-1-test
  (testing "Confidence is clamped to 1.0 even when count exceeds the divisor"
    (let [signals (vec (repeatedly 50 #(failure-signal-for :implement)))
          [pat] (sut/detect-repeated-failures signals 3)]
      (is (= 1.0 (:pattern/confidence pat))))))

(deftest detect-repeated-failures-groups-by-phase-test
  (testing "Failures across distinct phases yield separate patterns"
    (let [signals (concat (repeatedly 3 #(failure-signal-for :implement))
                          (repeatedly 3 #(failure-signal-for :verify)))
          patterns (sut/detect-repeated-failures signals 3)
          phases (set (map :pattern/phase patterns))]
      (is (= #{:implement :verify} phases)))))

;------------------------------------------------------------------------------ Layer 2
;; detect-rollback-patterns

(deftest detect-rollback-patterns-test
  (testing "Frequent rollbacks from the same phase yield a :frequent-rollback pattern"
    (let [signals (concat (repeatedly 3 #(rollback-signal-from-to :implement :plan))
                          [(rollback-signal-from-to :implement :review)])
          [pat] (sut/detect-rollback-patterns signals 3)]
      (is (= :frequent-rollback (:pattern/type pat)))
      (is (= :implement (:pattern/from-phase pat)))
      (is (= 4 (:pattern/occurrences pat)))
      (is (= #{:plan :review} (set (:pattern/to-phases pat)))))))

(deftest detect-rollback-patterns-below-threshold-test
  (testing "Fewer than min-occurrences rollbacks from one phase yield no pattern"
    (is (empty? (sut/detect-rollback-patterns
                 [(rollback-signal-from-to :implement :plan)]
                 3)))))

;------------------------------------------------------------------------------ Layer 2
;; detect-repair-patterns

(deftest detect-repair-patterns-test
  (testing "Recurring repairs of the same error type yield a :recurring-repair pattern"
    (let [signals (vec (repeatedly 3 #(repair-signal-for :missing-import)))
          [pat] (sut/detect-repair-patterns signals 3)]
      (is (= :recurring-repair (:pattern/type pat)))
      (is (= :missing-import (:pattern/error-type pat)))
      (is (= 3 (:pattern/occurrences pat))))))

;------------------------------------------------------------------------------ Layer 2
;; SimplePatternDetector — protocol composition

(deftest pattern-detector-detect-combines-all-three-detectors-test
  (testing "SimplePatternDetector runs all three detectors and concatenates results"
    (let [detector (sut/create-pattern-detector
                    {:min-pattern-occurrences 3})
          signals (concat (repeatedly 3 #(failure-signal-for :implement))
                          (repeatedly 3 #(rollback-signal-from-to :review :plan))
                          (repeatedly 3 #(repair-signal-for :missing-paren)))
          patterns (proto/detect detector signals)]
      (is (= #{:repeated-phase-failure :frequent-rollback :recurring-repair}
             (set (map :pattern/type patterns)))))))

(deftest pattern-detector-get-pattern-types-test
  (testing "get-pattern-types reports the three documented detected types"
    (is (= #{:repeated-phase-failure :frequent-rollback :recurring-repair}
           (proto/get-pattern-types (sut/create-pattern-detector))))))

;------------------------------------------------------------------------------ Layer 3
;; Improvement generators

(deftest generate-for-repeated-failure-shape-test
  (testing "generate-for-repeated-failure produces a :gate-adjustment proposal"
    (let [pattern {:pattern/type :repeated-phase-failure
                   :pattern/phase :implement
                   :pattern/occurrences 5
                   :pattern/confidence 0.8}
          imp (sut/generate-for-repeated-failure pattern)]
      (is (uuid? (:improvement/id imp)))
      (is (= :gate-adjustment (:improvement/type imp)))
      (is (= :implement (:improvement/target imp)))
      (is (= 0.8 (:improvement/confidence imp)))
      (is (= :proposed (:improvement/status imp)))
      (is (= pattern (:improvement/source-pattern imp)))
      (is (string? (:improvement/rationale imp))))))

(deftest generate-for-frequent-rollback-shape-test
  (testing "generate-for-frequent-rollback produces a :workflow-modification proposal"
    (let [pattern {:pattern/type :frequent-rollback
                   :pattern/from-phase :review
                   :pattern/to-phases [:plan :implement]
                   :pattern/occurrences 4
                   :pattern/confidence 0.6}
          imp (sut/generate-for-frequent-rollback pattern)]
      (is (= :workflow-modification (:improvement/type imp)))
      (is (= :review (:improvement/target imp)))
      (is (= [:plan :implement] (-> imp :improvement/change :rollback-targets))))))

(deftest generate-for-recurring-repair-shape-test
  (testing "generate-for-recurring-repair produces a :rule-addition proposal"
    (let [pattern {:pattern/type :recurring-repair
                   :pattern/error-type :missing-import
                   :pattern/occurrences 3
                   :pattern/confidence 0.5}
          imp (sut/generate-for-recurring-repair pattern)]
      (is (= :rule-addition (:improvement/type imp)))
      (is (= :knowledge-base (:improvement/target imp)))
      (is (= :missing-import (-> imp :improvement/change :error-type))))))

;------------------------------------------------------------------------------ Layer 3
;; SimpleImprovementGenerator — protocol composition

(deftest improvement-generator-dispatches-by-pattern-type-test
  (testing "Each pattern type generates the corresponding improvement type"
    (let [gen (sut/create-improvement-generator)
          patterns [{:pattern/type :repeated-phase-failure :pattern/phase :implement
                     :pattern/occurrences 3 :pattern/confidence 0.5}
                    {:pattern/type :frequent-rollback :pattern/from-phase :review
                     :pattern/to-phases [:plan] :pattern/occurrences 3 :pattern/confidence 0.5}
                    {:pattern/type :recurring-repair :pattern/error-type :err
                     :pattern/occurrences 3 :pattern/confidence 0.5}
                    ;; Unknown pattern type is dropped (case default returns []).
                    {:pattern/type :totally-unknown :pattern/occurrences 99}]
          imps (proto/generate-improvements gen patterns {})
          types (set (map :improvement/type imps))]
      (is (= 3 (count imps)))
      (is (= #{:gate-adjustment :workflow-modification :rule-addition} types)))))

(deftest improvement-generator-supported-patterns-test
  (testing "get-supported-patterns reports the three handled pattern types"
    (is (= #{:repeated-phase-failure :frequent-rollback :recurring-repair}
           (proto/get-supported-patterns (sut/create-improvement-generator))))))

;------------------------------------------------------------------------------ Layer 4
;; SimpleGovernance

(deftest governance-requires-approval-for-policy-update-test
  (testing "Policy update is always behind approval, regardless of confidence"
    (let [g (sut/create-governance)]
      (is (true? (proto/requires-approval? g
                                           {:improvement/type :policy-update
                                            :improvement/confidence 1.0}))))))

(deftest governance-requires-approval-for-workflow-modification-test
  (testing "Workflow modification is also always behind approval"
    (let [g (sut/create-governance)]
      (is (true? (proto/requires-approval? g
                                           {:improvement/type :workflow-modification
                                            :improvement/confidence 1.0}))))))

(deftest governance-low-confidence-requires-approval-test
  (testing "Confidence below auto-apply-threshold triggers approval even for safe types"
    (let [g (sut/create-governance)]
      (is (true? (proto/requires-approval? g
                                           {:improvement/type :gate-adjustment
                                            :improvement/confidence 0.5}))))))

(deftest governance-high-confidence-safe-type-can-auto-apply-test
  (testing "Safe type at high confidence can auto-apply"
    (let [g (sut/create-governance)
          imp {:improvement/type :gate-adjustment
               :improvement/confidence 0.99}]
      (is (false? (proto/requires-approval? g imp)))
      (is (true? (proto/can-auto-apply? g imp))))))

(deftest governance-approval-policy-by-type-test
  (testing "get-approval-policy returns documented policy for each known type"
    (let [g (sut/create-governance)]
      (is (false? (-> (proto/get-approval-policy g :prompt-change)         :auto-approve?)))
      (is (true?  (-> (proto/get-approval-policy g :gate-adjustment)       :auto-approve?)))
      (is (false? (-> (proto/get-approval-policy g :policy-update)         :auto-approve?)))
      (is (true?  (-> (proto/get-approval-policy g :rule-addition)         :auto-approve?)))
      (is (true?  (-> (proto/get-approval-policy g :budget-adjustment)     :auto-approve?)))
      (is (false? (-> (proto/get-approval-policy g :workflow-modification) :auto-approve?))))))

(deftest governance-approval-policy-default-test
  (testing "Unknown improvement types fall back to approval-required default"
    (let [g (sut/create-governance)
          policy (proto/get-approval-policy g :totally-unknown-type)]
      (is (false? (:auto-approve? policy)))
      (is (number? (:required-confidence policy))))))
