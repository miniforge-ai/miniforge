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

(ns ai.miniforge.boundary.interface.exception-to-anomaly-test
  "Exception → anomaly conversion. A throw inside the wrapped function
   must produce an anomaly step on the chain — the throw never escapes
   `execute`."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.boundary.interface :as boundary]
   [ai.miniforge.response-chain.interface :as chain]))

(defn- boom! [_]
  (throw (RuntimeException. "kaboom")))

(deftest thrown-exception-becomes-anomaly-step
  (testing "the appended step carries an anomaly and succeeded? false"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :op/risky
                              boom!
                              :ignored)
          step (last (chain/steps c))]
      (is (false? (:succeeded? step)))
      (is (anomaly/anomaly? (:anomaly step)))
      (is (nil? (:response step))))))

(deftest thrown-exception-flips-chain-failed
  (testing "chain/succeeded? is false once a wrapped throw is recorded"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :op/risky
                              boom!
                              :ignored)]
      (is (false? (chain/succeeded? c))))))

(deftest later-success-does-not-recover-failed-chain
  (testing "subsequent successful execute calls cannot un-fail the chain"
    (let [c (as-> (chain/create-chain :flow) c
              (boundary/execute :unknown c :a (constantly 1))
              (boundary/execute :unknown c :b boom! :ignored)
              (boundary/execute :unknown c :c (constantly 3)))]
      (is (false? (chain/succeeded? c)))
      (is (= 3 (count (chain/steps c)))))))

(deftest anomaly-message-defaults-to-exception-class-when-message-nil
  (testing "boundary still produces a non-nil :anomaly/message even when (.getMessage e) is nil"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :op/silent
                              (fn [] (throw (RuntimeException.))))
          msg (:anomaly/message (chain/last-anomaly c))]
      (is (string? msg))
      (is (not (clojure.string/blank? msg))))))
