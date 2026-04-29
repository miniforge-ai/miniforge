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

(ns ai.miniforge.response-chain.interface.append-step-anomaly-test
  "Anomaly-step append. The 4-arity overload with a non-nil anomaly
   must record a failed step and flip the chain's `:succeeded?` to false
   irrevocably."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest anomaly-step-marks-itself-failed
  (testing "step with non-nil anomaly has succeeded? false"
    (let [a (anomaly/anomaly :not-found "missing" {:id 1})
          c (-> (chain/create-chain :flow)
                (chain/append-step :db/lookup a nil))
          s (last (chain/steps c))]
      (is (false? (:succeeded? s)))
      (is (= a (:anomaly s)))
      (is (nil? (:response s))))))

(deftest anomaly-step-flips-chain-succeeded-false
  (testing "chain :succeeded? becomes false once any step fails"
    (let [a (anomaly/anomaly :fault "boom" {})
          c (-> (chain/create-chain :flow)
                (chain/append-step :a 1)
                (chain/append-step :b a nil))]
      (is (false? (chain/succeeded? c))))))

(deftest later-success-does-not-recover-failed-chain
  (testing "once any step has failed, the chain stays failed"
    (let [a (anomaly/anomaly :fault "boom" {})
          c (-> (chain/create-chain :flow)
                (chain/append-step :a 1)
                (chain/append-step :b a nil)
                (chain/append-step :c 2))]
      (is (false? (chain/succeeded? c)))
      (is (= 3 (count (chain/steps c)))))))

(deftest four-arity-with-nil-anomaly-is-success
  (testing "4-arity with explicit nil anomaly behaves like the 3-arity"
    (let [c (-> (chain/create-chain :flow)
                (chain/append-step :a nil 42))
          s (last (chain/steps c))]
      (is (true? (:succeeded? s)))
      (is (nil? (:anomaly s)))
      (is (= 42 (:response s)))
      (is (chain/succeeded? c)))))
