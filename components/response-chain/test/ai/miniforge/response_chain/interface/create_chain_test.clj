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

(ns ai.miniforge.response-chain.interface.create-chain-test
  "Initial-state happy path. A fresh chain must have the right
   operation, an empty step vector, and a vacuously-true `:succeeded?`."
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest create-chain-returns-canonical-shape
  (testing "fresh chain has the three canonical keys"
    (let [c (chain/create-chain :auth/login)]
      (is (= :auth/login (:operation c)))
      (is (true? (:succeeded? c)))
      (is (= [] (:response-chain c))))))

(deftest fresh-chain-validates-against-the-schema
  (testing "an empty chain matches the malli Chain schema"
    (is (m/validate chain/Chain (chain/create-chain :anything)))))

(deftest fresh-chain-is-vacuously-successful
  (testing "succeeded? returns true for a chain with no steps"
    (is (true? (chain/succeeded? (chain/create-chain :x))))))

(deftest fresh-chain-has-empty-step-vector
  (testing "steps returns an empty vector for a fresh chain"
    (is (= [] (chain/steps (chain/create-chain :x))))))

(deftest fresh-chain-has-no-last-response-or-anomaly
  (testing "last-response and last-anomaly are nil on an empty chain"
    (let [c (chain/create-chain :x)]
      (is (nil? (chain/last-response c)))
      (is (nil? (chain/last-anomaly c))))))
