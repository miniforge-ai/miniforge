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
(ns ai.miniforge.deliberation-workspace.transaction-test
  (:require
   [ai.miniforge.deliberation-workspace.transaction :as tx]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} vocabulary-and-classes-agree
  (testing "every operation in the vocabulary carries a concurrency class"
    (is (= tx/operations (set (keys tx/operation-class)))))
  (testing "no class table entry escapes the vocabulary"
    (is (every? tx/known-operation? (keys tx/operation-class))))
  (testing "classes come from the closed N14 §3.3 set"
    (is (= #{:additive :mergeable :exclusive} (set (vals tx/operation-class))))))

(deftest ^{:stratum 0} split-hypothesis-is-exclusive
  (testing "rewriting a hypothesis into parts needs the current version"
    (is (= :exclusive (tx/class-of :split-hypothesis)))))

(deftest ^{:stratum 0} decision-commitment-is-exclusive
  (testing "accept and reject must not commit against a stale basis"
    (is (= :exclusive (tx/class-of :accept-decision)))
    (is (= :exclusive (tx/class-of :reject-decision)))
    (is (= :exclusive (tx/class-of :close-goal)))))

(deftest ^{:stratum 0} additive-operations-commute
  (testing "proposals and evidence attachment never need a fresh basis"
    (is (= :additive (tx/class-of :assert-claim)))
    (is (= :additive (tx/class-of :attach-evidence)))
    (is (= :additive (tx/class-of :challenge)))
    (is (= :additive (tx/class-of :propose-hypothesis)))))

(deftest ^{:stratum 0} unknown-operations-are-rejected
  (is (not (tx/known-operation? :rewrite-history)))
  (is (nil? (tx/class-of :rewrite-history))))

(deftest ^{:stratum 0} role-permissions-restrict-the-right-operations
  (testing "only the verifier records experiment results"
    (is (tx/permitted? :verifier :record-experiment-result))
    (is (not (tx/permitted? :implementer :record-experiment-result))))
  (testing "only the synthesizer commits decisions and closes goals"
    (is (tx/permitted? :synthesizer :accept-decision))
    (is (not (tx/permitted? :skeptic :accept-decision)))
    (is (not (tx/permitted? :proposer :close-goal))))
  (testing "only the interpreter seeds goals and spec constraints"
    (is (tx/permitted? :interpreter :add-constraint))
    (is (tx/permitted? :interpreter :add-goal))
    (is (not (tx/permitted? :proposer :add-constraint))))
  (testing "only the meta-watchdog retires questions"
    (is (tx/permitted? :meta-watchdog :retire-question))
    (is (not (tx/permitted? :synthesizer :retire-question)))))

(deftest ^{:stratum 0} universal-operations-are-open-to-every-role
  (doseq [role [:interpreter :proposer :implementer :skeptic :verifier
                :synthesizer :meta-watchdog]]
    (is (tx/permitted? role :assert-claim))
    (is (tx/permitted? role :add-question))
    (is (tx/permitted? role :declare-blocked))))

(deftest ^{:stratum 0} restricted-operations-name-only-known-operations
  (is (every? tx/known-operation? (keys tx/role-permissions))))

(deftest ^{:stratum 0} transactions-carry-their-basis
  (let [t (tx/new-transaction {:role :skeptic :activation "act-7" :basis 143
                               :operations [{:op :challenge :targets #{"claim-27"}}]})]
    (is (= 143 (:tx/basis t)))
    (is (= :skeptic (:tx/role t)))
    (is (vector? (:tx/operations t)))))

(deftest ^{:stratum 0} touched-ids-are-declared-not-inferred
  (is (= #{"claim-27"} (tx/touched-ids {:op :challenge :targets #{"claim-27"}})))
  (is (= #{} (tx/touched-ids {:op :add-question}))))
