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

(ns ai.miniforge.generated.impl-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.generated.impl :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Return structure contract

(deftest execute-returns-map-test
  (testing "execute always returns a map"
    (is (map? (sut/execute nil)))
    (is (map? (sut/execute {})))
    (is (map? (sut/execute "hello")))
    (is (map? (sut/execute 42)))))

(deftest execute-status-test
  (testing "execute returns :not-implemented status"
    (is (= :not-implemented (:status (sut/execute nil))))
    (is (= :not-implemented (:status (sut/execute {}))))
    (is (= :not-implemented (:status (sut/execute :some-keyword))))))

(deftest execute-echoes-input-test
  (testing "execute echoes the exact input back"
    (is (= nil (:input (sut/execute nil))))
    (is (= {} (:input (sut/execute {}))))
    (is (= {:foo :bar} (:input (sut/execute {:foo :bar}))))
    (is (= "hello" (:input (sut/execute "hello"))))
    (is (= 42 (:input (sut/execute 42))))
    (is (= [1 2 3] (:input (sut/execute [1 2 3]))))))

(deftest execute-exact-keys-test
  (testing "execute returns exactly :status and :input keys"
    (let [result (sut/execute {:data 1})]
      (is (= #{:status :input} (set (keys result)))))))

;------------------------------------------------------------------------------ Layer 1
;; Edge-case inputs

(deftest execute-nil-input-test
  (testing "nil input produces well-formed result"
    (let [result (sut/execute nil)]
      (is (= {:status :not-implemented :input nil} result)))))

(deftest execute-empty-map-input-test
  (testing "empty map input is preserved"
    (is (= {:status :not-implemented :input {}} (sut/execute {})))))

(deftest execute-nested-input-test
  (testing "deeply nested input is preserved verbatim"
    (let [deep {:a {:b {:c {:d [1 2 {:e 3}]}}}}]
      (is (= deep (:input (sut/execute deep)))))))

(deftest execute-large-collection-input-test
  (testing "large vector input is preserved"
    (let [big-vec (vec (range 1000))]
      (is (= big-vec (:input (sut/execute big-vec)))))))

(deftest execute-boolean-input-test
  (testing "boolean inputs are preserved"
    (is (= true (:input (sut/execute true))))
    (is (= false (:input (sut/execute false))))))

(deftest execute-keyword-input-test
  (testing "keyword input is preserved"
    (is (= :some/qualified-kw (:input (sut/execute :some/qualified-kw))))))

;------------------------------------------------------------------------------ Layer 2
;; Identity / referential transparency

(deftest execute-deterministic-test
  (testing "same input always produces same output"
    (let [input {:task :build :params [1 2 3]}]
      (is (= (sut/execute input) (sut/execute input)))
      (is (= (sut/execute input) (sut/execute input))))))

(deftest execute-no-mutation-test
  (testing "execute does not mutate the input map"
    (let [input {:mutable :data}
          _ (sut/execute input)]
      (is (= {:mutable :data} input)))))

(deftest execute-identity-on-input-test
  (testing "the :input value is identical (not just equal) to the argument"
    (let [input {:x 1}
          result (sut/execute input)]
      (is (identical? input (:input result))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.generated.impl-test)

  :leave-this-here)
