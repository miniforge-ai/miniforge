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

(ns ai.miniforge.response-chain.interface.append-step-request-test
  "5-arity append-step that carries the original request as :request
   metadata on the step. Used by downstream consumers (e.g. an
   inference-evidence bridge) that need to reconstruct what was asked,
   not just what came back."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response-chain.interface :as chain]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]))

(deftest five-arity-success-attaches-request-to-step
  (testing "5-arity success path records :request on the step"
    (let [request {:lookup [:person/id "chris"]}
          c0     (chain/create-chain :flow)
          c1     (chain/append-step c0
                                    :db/find-user
                                    nil
                                    {:id "chris" :name "Christopher Lester"}
                                    request)
          step   (last (chain/steps c1))]
      (is (chain/succeeded? c1))
      (is (= request (:request step)))
      (is (= {:id "chris" :name "Christopher Lester"} (:response step))))))

(deftest five-arity-anomaly-attaches-request-to-step
  (testing "5-arity anomaly path also records :request on the step"
    (let [request {:lookup []}
          an      (anomaly/anomaly :invalid-input "empty lookup" {})
          c0      (chain/create-chain :flow)
          c1      (chain/append-step c0
                                     :db/find-user
                                     an
                                     request
                                     request)
          step    (last (chain/steps c1))]
      (is (false? (chain/succeeded? c1)))
      (is (= request (:request step)))
      (is (= an (:anomaly step))))))

(deftest three-arity-omits-request-key
  (testing "3-arity success path produces a step WITHOUT :request key"
    (let [c0   (chain/create-chain :flow)
          c1   (chain/append-step c0 :op {:result 1})
          step (last (chain/steps c1))]
      (is (not (contains? step :request))))))

(deftest four-arity-omits-request-key
  (testing "4-arity (existing API) produces a step WITHOUT :request key"
    (let [c0   (chain/create-chain :flow)
          c1   (chain/append-step c0 :op nil {:result 1})
          step (last (chain/steps c1))]
      (is (not (contains? step :request))))))

(deftest five-arity-with-nil-request-omits-key
  (testing "5-arity with explicit nil request omits :request key"
    (let [c0   (chain/create-chain :flow)
          c1   (chain/append-step c0 :op nil {:result 1} nil)
          step (last (chain/steps c1))]
      (is (not (contains? step :request)))
      (is (chain/succeeded? c1)))))

(deftest schema-validates-step-with-request
  (testing "Step schema accepts steps that carry :request"
    (let [c0   (chain/create-chain :flow)
          c1   (chain/append-step c0 :op nil "ok" {:original "request"})
          step (last (chain/steps c1))]
      (is (m/validate chain/Step step))
      (is (= {:original "request"} (:request step))))))

(deftest schema-validates-step-without-request
  (testing "Step schema still accepts steps that omit :request (back-compat)"
    (let [c0   (chain/create-chain :flow)
          c1   (chain/append-step c0 :op {:result 1})
          step (last (chain/steps c1))]
      (is (m/validate chain/Step step))
      (is (not (contains? step :request))))))

(deftest schema-validates-chain-with-mixed-steps
  (testing "Chain schema accepts a mix of steps with and without :request"
    (let [c (-> (chain/create-chain :flow)
                (chain/append-step :step-1 {:result 1})
                (chain/append-step :step-2 nil {:result 2} {:request 2})
                (chain/append-step :step-3 {:result 3}))]
      (is (m/validate chain/Chain c))
      (is (= 3 (count (chain/steps c))))
      (is (not (contains? (nth (chain/steps c) 0) :request)))
      (is (contains?     (nth (chain/steps c) 1) :request))
      (is (not (contains? (nth (chain/steps c) 2) :request))))))

(deftest non-keyword-operation-still-records-request
  (testing "non-keyword operation in 5-arity still surfaces :invalid-input AND records the request"
    (let [request {:lookup [:person/id "chris"]}
          c0      (chain/create-chain :flow)
          c1      (chain/append-step c0 "string-op" nil "result" request)
          step    (last (chain/steps c1))]
      (is (false? (chain/succeeded? c1)))
      (is (= :response-chain/invalid (:operation step)))
      (is (= :invalid-input (-> step :anomaly :anomaly/type)))
      (is (= request (:request step))))))

(deftest request-can-be-arbitrary-edn
  (testing ":request accepts any EDN-shape value"
    (doseq [req [{:map "value"}
                 [:vector :of :keywords]
                 #{:set :of :things}
                 "a string"
                 42
                 :a-keyword]]
      (let [c0   (chain/create-chain :flow)
            c1   (chain/append-step c0 :op nil "ok" req)
            step (last (chain/steps c1))]
        (is (m/validate chain/Step step))
        (is (= req (:request step)))))))
