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
(ns ai.miniforge.dag-primitives.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dag-primitives.interface :as dp]))

;------------------------------------------------------------------------------ Layer 0

;;------------------------------------------------------------------------------ Topological sort
(deftest ^{:stratum 0} topological-sort-linear-chain
  (testing "A → B → C returns [A B C]"
    (let [result (dp/topological-sort {:a #{} :b #{:a} :c #{:b}})]
      (is (dp/ok? result))
      (is (= [:a :b :c] (:data result))))))

(deftest ^{:stratum 0} topological-sort-parallel-roots
  (testing "Two independent roots both precede their shared dependent"
    (let [result (dp/topological-sort {:a #{} :b #{} :c #{:a :b}})]
      (is (dp/ok? result))
      (let [order (:data result)]
        (is (= 3 (count order)))
        (is (< (.indexOf order :a) (.indexOf order :c)))
        (is (< (.indexOf order :b) (.indexOf order :c)))))))

(deftest ^{:stratum 0} topological-sort-diamond
  (testing "Diamond: A → B, A → C, B → D, C → D"
    (let [result (dp/topological-sort {:a #{} :b #{:a} :c #{:a} :d #{:b :c}})]
      (is (dp/ok? result))
      (let [order (:data result)]
        (is (= 4 (count order)))
        (is (= :a (first order)))
        (is (= :d (last order)))))))

(deftest ^{:stratum 0} topological-sort-single-node
  (testing "Single node with no deps"
    (let [result (dp/topological-sort {:a #{}})]
      (is (dp/ok? result))
      (is (= [:a] (:data result))))))

(deftest ^{:stratum 0} topological-sort-empty
  (testing "Empty graph"
    (let [result (dp/topological-sort {})]
      (is (dp/ok? result))
      (is (= [] (:data result))))))

(deftest ^{:stratum 0} topological-sort-cycle-detected
  (testing "Cycle returns err with :cycle-detected"
    (let [result (dp/topological-sort {:a #{:c} :b #{:a} :c #{:b}})]
      (is (dp/err? result))
      (is (= :cycle-detected (get-in result [:error :code])))
      (is (= #{:a :b :c} (get-in result [:error :data :cycle-nodes]))))))

(deftest ^{:stratum 0} topological-sort-partial-cycle
  (testing "Cycle in part of graph; acyclic nodes still processed"
    (let [result (dp/topological-sort {:root #{} :a #{:b} :b #{:a}})]
      (is (dp/err? result))
      (is (= :cycle-detected (get-in result [:error :code])))
      (is (= #{:a :b} (get-in result [:error :data :cycle-nodes]))))))

;;------------------------------------------------------------------------------ Result monad
(deftest ^{:stratum 0} ok-construction
  (is (dp/ok? (dp/ok {:x 1})))
  (is (= {:x 1} (:data (dp/ok {:x 1})))))

(deftest ^{:stratum 0} err-construction
  (is (dp/err? (dp/err :bad "oops")))
  (is (= :bad (get-in (dp/err :bad "oops") [:error :code])))
  (is (= {:detail "x"} (get-in (dp/err :bad "oops" {:detail "x"}) [:error :data]))))

(deftest ^{:stratum 0} unwrap-ok
  (is (= 42 (dp/unwrap (dp/ok 42)))))

(deftest ^{:stratum 0} unwrap-err-throws
  (is (thrown? Exception (dp/unwrap (dp/err :e "e")))))

(deftest ^{:stratum 0} unwrap-or-default
  (is (= :fallback (dp/unwrap-or (dp/err :e "e") :fallback))))

(deftest ^{:stratum 0} map-ok-transforms-data
  (let [result (dp/map-ok (dp/ok 5) inc)]
    (is (dp/ok? result))
    (is (= 6 (:data result)))))

(deftest ^{:stratum 0} map-ok-passes-through-err
  (let [e (dp/err :e "e")]
    (is (= e (dp/map-ok e inc)))))

(deftest ^{:stratum 0} and-then-chains
  (let [result (dp/and-then (dp/ok 5) #(dp/ok (* % 2)))]
    (is (dp/ok? result))
    (is (= 10 (:data result)))))

(deftest ^{:stratum 0} and-then-short-circuits-on-err
  (let [e      (dp/err :e "e")
        result (dp/and-then e #(dp/ok (inc %)))]
    (is (= e result))))

(deftest ^{:stratum 0} collect-all-ok
  (let [result (dp/collect [(dp/ok 1) (dp/ok 2) (dp/ok 3)])]
    (is (dp/ok? result))
    (is (= [1 2 3] (:data result)))))

(deftest ^{:stratum 0} collect-first-err-short-circuits
  (let [e      (dp/err :bad "bad")
        result (dp/collect [(dp/ok 1) e (dp/ok 3)])]
    (is (= e result))))
