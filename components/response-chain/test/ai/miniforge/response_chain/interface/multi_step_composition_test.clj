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

(ns ai.miniforge.response-chain.interface.multi-step-composition-test
  "Threading semantics. The chain is a linear accumulator: order is
   preserved, the chain stays well-formed across many appends, and the
   `:succeeded?` invariant is recomputed on every append."
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest steps-preserve-append-order
  (testing "operations come back in the order they were appended"
    (let [c (-> (chain/create-chain :flow)
                (chain/append-step :a 1)
                (chain/append-step :b 2)
                (chain/append-step :c 3)
                (chain/append-step :d 4))]
      (is (= [:a :b :c :d]
             (mapv :operation (chain/steps c)))))))

(deftest responses-are-paired-with-their-operations
  (testing "each step's :response is the value passed at append time"
    (let [c (-> (chain/create-chain :flow)
                (chain/append-step :a "first")
                (chain/append-step :b "second")
                (chain/append-step :c "third"))]
      (is (= [["first"  :a]
              ["second" :b]
              ["third"  :c]]
             (mapv (juxt :response :operation) (chain/steps c)))))))

(deftest chain-shape-stays-valid-through-many-appends
  (testing "after each append the chain still validates against the schema"
    (let [a (anomaly/anomaly :fault "boom" {})
          c (-> (chain/create-chain :flow)
                (chain/append-step :a 1)
                (chain/append-step :b a nil)
                (chain/append-step :c 3))]
      (is (m/validate chain/Chain c))
      (is (every? #(m/validate chain/Step %) (chain/steps c))))))

(deftest succeeded-invariant-recomputed-each-append
  (testing "chain :succeeded? equals the AND of all step :succeeded? after each append"
    (let [a (anomaly/anomaly :fault "boom" {})
          c0 (chain/create-chain :flow)
          c1 (chain/append-step c0 :a 1)
          c2 (chain/append-step c1 :b a nil)
          c3 (chain/append-step c2 :c 3)]
      (is (true?  (:succeeded? c0)))
      (is (true?  (:succeeded? c1)))
      (is (false? (:succeeded? c2)))
      (is (false? (:succeeded? c3))))))

(deftest threading-is-pure-and-non-mutating
  (testing "each append returns a new chain; the original is unchanged"
    (let [c0 (chain/create-chain :flow)
          c1 (chain/append-step c0 :a 1)]
      (is (= 0 (count (chain/steps c0))))
      (is (= 1 (count (chain/steps c1)))))))
