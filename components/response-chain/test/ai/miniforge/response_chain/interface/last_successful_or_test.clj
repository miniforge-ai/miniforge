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

(ns ai.miniforge.response-chain.interface.last-successful-or-test
  "`last-successful-or` returns the most recent successful step's
   `:response`, falling back to a caller-supplied default when none has
   been recorded."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest fallback-default-on-empty-chain
  (testing "no steps yet => default is returned"
    (is (= ::none (chain/last-successful-or (chain/create-chain :x) ::none)))))

(deftest fallback-default-when-no-step-succeeded
  (testing "every step failed => default is returned"
    (let [a (anomaly/anomaly :fault "boom" {})
          c (-> (chain/create-chain :x)
                (chain/append-step :a a nil)
                (chain/append-step :b a nil))]
      (is (= ::fallback (chain/last-successful-or c ::fallback))))))

(deftest returns-most-recent-successful-response
  (testing "with mixed failures, returns the most recent successful step's response"
    (let [a (anomaly/anomaly :fault "boom" {})
          c (-> (chain/create-chain :x)
                (chain/append-step :a 1)
                (chain/append-step :b 2)
                (chain/append-step :c a nil))]
      (is (= 2 (chain/last-successful-or c ::none))))))

(deftest returns-most-recent-success-after-recovery
  (testing "if a later step succeeds, that is the most recent success"
    (let [a (anomaly/anomaly :fault "boom" {})
          c (-> (chain/create-chain :x)
                (chain/append-step :a 1)
                (chain/append-step :b a nil)
                (chain/append-step :c 3))]
      (is (= 3 (chain/last-successful-or c ::none))))))

(deftest default-can-be-any-value
  (testing "default may be nil, a map, or any value"
    (let [c (chain/create-chain :x)]
      (is (nil? (chain/last-successful-or c nil)))
      (is (= {:k 1} (chain/last-successful-or c {:k 1}))))))
