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

(ns ai.miniforge.response-chain.interface.last-anomaly-test
  "`last-anomaly` returns the most recent step's `:anomaly` (or nil
   when the chain is empty / the last step succeeded)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest last-anomaly-nil-for-empty-chain
  (testing "no steps yet means no anomaly"
    (is (nil? (chain/last-anomaly (chain/create-chain :x))))))

(deftest last-anomaly-nil-when-last-step-succeeded
  (testing "last successful step reports nil anomaly"
    (let [c (-> (chain/create-chain :x)
                (chain/append-step :a 1))]
      (is (nil? (chain/last-anomaly c))))))

(deftest last-anomaly-returns-anomaly-for-failed-step
  (testing "non-nil anomaly is returned when the last step failed"
    (let [a (anomaly/anomaly :not-found "missing" {:id 7})
          c (-> (chain/create-chain :x)
                (chain/append-step :a a nil))]
      (is (= a (chain/last-anomaly c))))))

(deftest last-anomaly-tracks-the-last-step-not-the-first-failure
  (testing "if the failure is followed by a success, last-anomaly is nil"
    (let [a (anomaly/anomaly :fault "boom" {})
          c (-> (chain/create-chain :x)
                (chain/append-step :a a nil)
                (chain/append-step :b 2))]
      (is (nil? (chain/last-anomaly c)))
      (is (false? (chain/succeeded? c))))))
