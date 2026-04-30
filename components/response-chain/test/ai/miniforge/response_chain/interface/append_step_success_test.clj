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

(ns ai.miniforge.response-chain.interface.append-step-success-test
  "Successful-step append. The 3-arity overload must record a step
   with `:succeeded? true`, no anomaly, and the carried response, while
   leaving the chain's `:succeeded?` true."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest append-step-3-arity-marks-step-successful
  (testing "appended step has succeeded? true and nil anomaly"
    (let [c (-> (chain/create-chain :flow)
                (chain/append-step :db/lookup {:id 1}))
          s (last (chain/steps c))]
      (is (true? (:succeeded? s)))
      (is (nil? (:anomaly s)))
      (is (= :db/lookup (:operation s)))
      (is (= {:id 1} (:response s))))))

(deftest append-step-3-arity-keeps-chain-successful
  (testing "chain :succeeded? remains true after an all-success run"
    (let [c (-> (chain/create-chain :flow)
                (chain/append-step :a 1)
                (chain/append-step :b 2))]
      (is (chain/succeeded? c)))))

(deftest append-step-preserves-operation-and-grows-step-vector
  (testing "the chain's :operation is preserved and steps grow by one"
    (let [c0 (chain/create-chain :flow)
          c1 (chain/append-step c0 :a 1)
          c2 (chain/append-step c1 :b 2)]
      (is (= :flow (:operation c2)))
      (is (= 0 (count (chain/steps c0))))
      (is (= 1 (count (chain/steps c1))))
      (is (= 2 (count (chain/steps c2)))))))

(deftest append-step-accepts-nil-response
  (testing "nil is a valid response value"
    (let [c (-> (chain/create-chain :flow)
                (chain/append-step :a nil))
          s (last (chain/steps c))]
      (is (nil? (:response s)))
      (is (true? (:succeeded? s))))))
