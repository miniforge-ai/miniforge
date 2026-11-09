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
(ns ai.miniforge.qualification.verdict-test
  "Unrun and unsupported criteria never count as a pass."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.qualification.schema :as schema]
            [ai.miniforge.qualification.verdict :as verdict]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(def ^{:stratum 0} ^:private gate
  {:gate/id       :fixture/gate
   :gate/title    "Fixture gate"
   :gate/criteria [{:criterion/id :c/one :criterion/description "First criterion"}
                   {:criterion/id :c/two :criterion/description "Second criterion"}]})

(defn- ^{:stratum 0} result [id status]
  {:criterion/id id :criterion/status status})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verdict-of [results]
  (verdict/verdict (verdict/rows gate results)))

(deftest ^{:stratum 1} test-missing-result-is-unrun-not-pass
  (testing "given no result for one criterion → that row is unrun and the verdict is incomplete"
    (let [rows (verdict/rows gate [(result :c/one :status/pass)])]
      (is (= [:status/pass :status/unrun] (mapv :criterion/status rows)))
      (is (string? (:criterion/reason (second rows))))
      (is (= :verdict/incomplete (verdict/verdict rows))))))

(deftest ^{:stratum 1} test-rows-keep-declaration-order-and-evidence
  (testing "given results out of order with evidence → rows follow the gate, evidence kept"
    (let [two  (assoc (result :c/two :status/pass)
                      :criterion/faults [{:fault/kind :transform/missing}]
                      :criterion/observed {:sink-calls 0})
          rows (verdict/rows gate [two (result :c/one :status/pass)])]
      (is (= [:c/one :c/two] (mapv :criterion/id rows)))
      (is (= {:sink-calls 0} (:criterion/observed (second rows))))
      (is (every? #(m/validate schema/Row %) rows)))))

(deftest ^{:stratum 1} test-undeclared-and-duplicate-results-are-problems
  (testing "given a result for an undeclared criterion and two for one criterion → two problems"
    (is (= 2 (count (verdict/result-problems gate [(result :c/other :status/pass)
                                                   (result :c/one :status/pass)
                                                   (result :c/one :status/fail)])))))
  (testing "given one result per declared criterion → no problems"
    (is (empty? (verdict/result-problems gate [(result :c/one :status/pass)])))))

(deftest ^{:stratum 1} test-unrun-rows-cover-every-criterion
  (testing "given a reason → every declared criterion is unrun with it"
    (let [rows (verdict/unrun-rows gate "scenario threw")]
      (is (= #{:status/unrun} (set (map :criterion/status rows))))
      (is (= #{"scenario threw"} (set (map :criterion/reason rows))))
      (is (= :verdict/incomplete (verdict/verdict rows))))))

(deftest ^{:stratum 1} test-gate-schema
  (testing "given a gate with an unqualified id or no criteria → invalid"
    (is (m/validate schema/Gate gate))
    (is (not (m/validate schema/Gate (assoc gate :gate/id :gate))))
    (is (not (m/validate schema/Gate (assoc gate :gate/criteria []))))))

;------------------------------------------------------------------------------ Layer 2

;; Tests
(deftest ^{:stratum 2} test-every-criterion-passing-is-a-pass
  (testing "given a pass for every declared criterion → :verdict/pass"
    (is (= :verdict/pass (verdict-of [(result :c/one :status/pass) (result :c/two :status/pass)])))))

(deftest ^{:stratum 2} test-unsupported-is-not-a-pass
  (testing "given an unsupported criterion and a pass → incomplete"
    (is (= :verdict/incomplete
           (verdict-of [(result :c/one :status/pass) (result :c/two :status/unsupported)])))))

(deftest ^{:stratum 2} test-any-failure-fails
  (testing "given one failure beside an unrun criterion → :verdict/fail"
    (is (= :verdict/fail (verdict-of [(result :c/one :status/fail)])))))
