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

(ns ai.miniforge.response-chain.interface.last-response-test
  "`last-response` returns the most recent step's `:response` value,
   regardless of success status, and nil for empty chains."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest last-response-nil-for-empty-chain
  (testing "empty chain has no last response"
    (is (nil? (chain/last-response (chain/create-chain :x))))))

(deftest last-response-returns-most-recent-success
  (testing "happy path: last response is the last appended response"
    (let [c (-> (chain/create-chain :x)
                (chain/append-step :a 1)
                (chain/append-step :b 2)
                (chain/append-step :c 3))]
      (is (= 3 (chain/last-response c))))))

(deftest last-response-of-failed-step-is-its-response
  (testing "even when the last step failed, last-response returns its :response"
    (let [a (anomaly/anomaly :fault "boom" {})
          c (-> (chain/create-chain :x)
                (chain/append-step :a 1)
                (chain/append-step :b a {:partial true}))]
      (is (= {:partial true} (chain/last-response c))))))

(deftest last-response-handles-nil-payload
  (testing "nil response on the last step is returned as nil"
    (let [c (-> (chain/create-chain :x)
                (chain/append-step :a 1)
                (chain/append-step :b nil))]
      (is (nil? (chain/last-response c))))))
