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

(ns ai.miniforge.boundary.interface.happy-path-test
  "Happy path. When the wrapped function returns normally, boundary
   appends a successful step under the supplied operation key, the
   chain stays successful, and the response is the function's return
   value."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.boundary.interface :as boundary]
   [ai.miniforge.response-chain.interface :as chain]))

(deftest successful-call-appends-successful-step
  (testing "execute on a returning function appends a step with succeeded? true"
    (let [c0 (chain/create-chain :flow)
          c1 (boundary/execute :db c0 :db/find-user
                               (fn [id] {:id id})
                               7)
          step (last (chain/steps c1))]
      (is (true? (:succeeded? step)))
      (is (nil?  (:anomaly step)))
      (is (= :db/find-user (:operation step))))))

(deftest successful-call-keeps-chain-succeeded
  (testing "chain remains successful after a successful execute"
    (let [c0 (chain/create-chain :flow)
          c  (boundary/execute :db c0 :db/find-user (constantly :ok))]
      (is (chain/succeeded? c)))))

(deftest response-is-the-functions-return-value
  (testing "step :response equals the value the wrapped function returned"
    (let [c (boundary/execute :io
                              (chain/create-chain :flow)
                              :io/read-file
                              (fn [path] {:path path :bytes 12})
                              "/tmp/x")]
      (is (= {:path "/tmp/x" :bytes 12} (chain/last-response c))))))

(deftest variadic-args-are-applied-in-order
  (testing "extra args after f are forwarded to (apply f args) in order"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :math/add
                              +
                              1 2 3 4)]
      (is (= 10 (chain/last-response c))))))

(deftest zero-arg-function-is-supported
  (testing "execute supports zero-arg wrapped functions"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :pure/now
                              (constantly 42))]
      (is (chain/succeeded? c))
      (is (= 42 (chain/last-response c))))))

(deftest execute-and-long-form-are-equivalent-on-success
  (testing "execute is a true alias for execute-with-exception-handling"
    (let [c0 (chain/create-chain :flow)
          a  (boundary/execute :db c0 :op (constantly :ok))
          b  (boundary/execute-with-exception-handling :db c0 :op (constantly :ok))]
      (is (= (chain/last-response a) (chain/last-response b)))
      (is (= (chain/succeeded? a)    (chain/succeeded? b))))))
