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

(ns ai.miniforge.boundary.interface.programmer-error-guard-test
  "Programmer-error guard. An unknown category is a wiring mistake at
   the call site, not a runtime condition — boundary throws
   `IllegalArgumentException` (mirrors `anomaly/anomaly`'s vocabulary
   check)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.boundary.interface :as boundary]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest unknown-category-throws-illegal-argument
  (testing "execute with a non-vocabulary category throws IllegalArgumentException"
    (is (thrown? IllegalArgumentException
                 (boundary/execute :no-such-category
                                   (chain/create-chain :flow)
                                   :op
                                   (constantly :ok))))))

(deftest unknown-category-on-long-form-also-throws
  (testing "execute-with-exception-handling enforces the same guard"
    (is (thrown? IllegalArgumentException
                 (boundary/execute-with-exception-handling
                  :bogus
                  (chain/create-chain :flow)
                  :op
                  (constantly :ok))))))

(deftest non-keyword-category-throws-illegal-argument
  (testing "a string in the category slot is a programmer error and throws"
    (is (thrown? IllegalArgumentException
                 (boundary/execute "db"
                                   (chain/create-chain :flow)
                                   :op
                                   (constantly :ok))))))

(deftest nil-category-throws-illegal-argument
  (testing "nil in the category slot is a programmer error and throws"
    (is (thrown? IllegalArgumentException
                 (boundary/execute nil
                                   (chain/create-chain :flow)
                                   :op
                                   (constantly :ok))))))

(deftest illegal-argument-message-lists-known-categories
  (testing "the thrown message lists the standard category vocabulary so the call site can self-correct"
    (try
      (boundary/execute :no-such-category
                        (chain/create-chain :flow)
                        :op
                        (constantly :ok))
      (is false "expected IllegalArgumentException")
      (catch IllegalArgumentException e
        (let [msg (.getMessage e)]
          (is (re-find #":no-such-category" msg))
          (is (re-find #":db" msg)))))))
