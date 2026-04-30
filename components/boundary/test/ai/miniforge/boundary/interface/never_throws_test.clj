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

(ns ai.miniforge.boundary.interface.never-throws-test
  "Boundary helper invariant. With a known category, no runtime input
   may cause `execute` to escape with a thrown exception. Every
   pathological case turns into a chain step instead."
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]
   [ai.miniforge.boundary.interface :as boundary]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest error-thrown-by-wrapped-fn-is-absorbed
  (testing "even a JVM Error subclass becomes an anomaly step"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :op
                              (fn [] (throw (AssertionError. "assert"))))]
      (is (false? (chain/succeeded? c)))
      (is (m/validate chain/Chain c)))))

(deftest deeply-nested-throw-is-absorbed
  (testing "a throw triggered by a chain of helpers still becomes a step"
    (let [boom (fn [] (throw (RuntimeException. "deep")))
          mid  (fn [] (boom))
          top  (fn [] (mid))
          c    (boundary/execute :unknown
                                 (chain/create-chain :flow)
                                 :op
                                 top)]
      (is (false? (chain/succeeded? c))))))

(deftest non-ifn-in-f-slot-becomes-anomaly-step
  (testing "passing a non-IFn (string) as f doesn't escape — apply throws, boundary captures it"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :op
                              "not-a-fn"
                              :ignored)]
      (is (m/validate chain/Chain c))
      (is (false? (chain/succeeded? c))))))

(deftest already-failed-chain-stays-failed
  (testing "execute on a chain that already has a failed step keeps the chain failed and well-formed"
    (let [c0 (chain/create-chain :flow)
          c1 (boundary/execute :unknown c0 :a (fn [] (throw (RuntimeException. "x"))))
          c2 (boundary/execute :unknown c1 :b (constantly :ok))]
      (is (m/validate chain/Chain c2))
      (is (false? (chain/succeeded? c2)))
      (is (= 2 (count (chain/steps c2)))))))
