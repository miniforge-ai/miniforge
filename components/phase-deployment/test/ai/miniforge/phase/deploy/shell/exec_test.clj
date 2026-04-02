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

(ns ai.miniforge.phase.deploy.shell.exec-test
  (:require [ai.miniforge.phase.deploy.shell.exec :as sut]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Shell execution tests

(deftest sh-with-timeout-test
  (testing "returns a structured success result for a successful command"
    (let [result (sut/sh-with-timeout "echo" ["hello"])]
      (is (:success? result))
      (is (= 0 (:exit-code result)))
      (is (str/includes? (:stdout result) "hello"))))

  (testing "returns a structured timeout result"
    (let [result (sut/sh-with-timeout "sleep" ["1"] :timeout-ms 10)]
      (is (false? (:success? result)))
      (is (= :timeout (:error-type result))))))

(deftest classify-error-test
  (testing "classifies retryable and permanent errors"
    (is (= :transient (sut/classify-error {:stderr "rate limit exceeded" :stdout ""})))
    (is (= :state-lock (sut/classify-error {:stderr "ConcurrentAccessError" :stdout ""})))
    (is (= :permanent (sut/classify-error {:stderr "permission denied" :stdout ""})))))
