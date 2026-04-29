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

(ns ai.miniforge.response-chain.interface.succeeded-predicate-test
  "Predicate semantics. `succeeded?` must work uniformly for steps and
   for chains, and must always return a boolean."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest succeeded?-for-empty-chain-is-true
  (testing "empty chain is vacuously successful"
    (is (true? (chain/succeeded? (chain/create-chain :x))))))

(deftest succeeded?-for-all-success-chain-is-true
  (testing "chain with only successful steps reports true"
    (let [c (-> (chain/create-chain :x)
                (chain/append-step :a 1)
                (chain/append-step :b 2)
                (chain/append-step :c 3))]
      (is (true? (chain/succeeded? c))))))

(deftest succeeded?-for-any-failed-chain-is-false
  (testing "any failed step makes the chain unsuccessful"
    (let [a (anomaly/anomaly :fault "boom" {})
          c (-> (chain/create-chain :x)
                (chain/append-step :a 1)
                (chain/append-step :b a nil))]
      (is (false? (chain/succeeded? c))))))

(deftest succeeded?-of-individual-steps
  (testing "succeeded? reads :succeeded? off a single step"
    (let [a (anomaly/anomaly :timeout "slow" {})
          c (-> (chain/create-chain :x)
                (chain/append-step :ok 1)
                (chain/append-step :nope a nil))
          [ok-step nope-step] (chain/steps c)]
      (is (true?  (chain/succeeded? ok-step)))
      (is (false? (chain/succeeded? nope-step))))))

(deftest succeeded?-always-returns-a-boolean
  (testing "result is true or false, never nil or a truthy non-boolean"
    (is (boolean? (chain/succeeded? (chain/create-chain :x))))
    (is (boolean? (chain/succeeded? {:succeeded? nil})))
    (is (false?   (chain/succeeded? {:succeeded? nil})))
    (is (true?    (chain/succeeded? {:succeeded? true})))))
