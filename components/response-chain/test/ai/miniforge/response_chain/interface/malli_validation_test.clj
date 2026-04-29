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

(ns ai.miniforge.response-chain.interface.malli-validation-test
  "Boundary validation. Malformed input must be rejected — but as data,
   not as a thrown exception. The component records an `:invalid-input`
   anomaly step and the chain stays well-formed."
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest fresh-chain-validates
  (testing "the canonical fresh chain validates against the Chain schema"
    (is (m/validate chain/Chain (chain/create-chain :x)))))

(deftest valid-step-validates
  (testing "a step produced by append-step matches the Step schema"
    (let [c (-> (chain/create-chain :x)
                (chain/append-step :a {:ok true}))]
      (is (every? #(m/validate chain/Step %) (chain/steps c))))))

(deftest append-on-malformed-chain-does-not-throw
  (testing "passing a non-chain to append-step returns a chain, not an exception"
    (let [result (chain/append-step {:not "a chain"} :op "value")]
      (is (map? result))
      (is (m/validate chain/Chain result)))))

(deftest append-on-malformed-chain-records-invalid-input-anomaly
  (testing "the recovery chain carries an :invalid-input anomaly step"
    (let [result (chain/append-step {:not "a chain"} :op "value")
          last-step (last (chain/steps result))]
      (is (false? (chain/succeeded? result)))
      (is (= :invalid-input (some-> last-step :anomaly :anomaly/type)))
      (is (anomaly/anomaly? (:anomaly last-step))))))

(deftest four-arity-with-non-anomaly-second-arg-is-rejected
  (testing "a non-Anomaly value in the anomaly slot is treated as malformed input"
    (let [c0 (chain/create-chain :x)
          result (chain/append-step c0 :op "not-an-anomaly" "value")
          last-step (last (chain/steps result))]
      (is (m/validate chain/Chain result))
      (is (false? (chain/succeeded? result)))
      (is (= :invalid-input (some-> last-step :anomaly :anomaly/type))))))

(deftest invalid-input-recovery-keeps-chain-well-formed
  (testing "even after recovery the resulting chain validates"
    (let [result (-> (chain/create-chain :x)
                     (chain/append-step :a 1)
                     (chain/append-step :b "not-an-anomaly" 2))]
      (is (m/validate chain/Chain result))
      (is (false? (chain/succeeded? result))))))
