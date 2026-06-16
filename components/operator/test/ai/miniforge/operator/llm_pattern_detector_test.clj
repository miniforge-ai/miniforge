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

(ns ai.miniforge.operator.llm-pattern-detector-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.llm.interface.protocols.llm-client :as llm-client]
            [ai.miniforge.operator.llm-pattern-detector :as sut]
            [ai.miniforge.operator.protocol :as proto]))

(defrecord FakeLLMClient [response requests]
  llm-client/LLMClient
  (complete* [_this request]
    (swap! requests conj request)
    response)
  (complete-stream* [_this request _on-chunk]
    (swap! requests conj request)
    response)
  (get-config [_this] {}))

(deftest detect-uses-public-llm-interface-test
  (testing "LLM pattern detector calls the client and parses JSON patterns"
    (let [requests (atom [])
          client (->FakeLLMClient {:success true
                                   :content "[{\"type\":\"anti-pattern\",\"description\":\"x\",\"affected\":\"implement\",\"occurrences\":2,\"confidence\":0.75,\"rationale\":\"because\"}]"}
                                  requests)
          detector (sut/create-llm-pattern-detector {:llm-client client})
          result (proto/detect detector [{:signal/type :workflow-failed
                                          :signal/data {:phase :implement}
                                          :signal/timestamp 1}])]
      (is (= 1 (count @requests)))
      (is (= [{:pattern/type :anti-pattern
               :pattern/description "x"
               :pattern/affected "implement"
               :pattern/occurrences 2
               :pattern/confidence 0.75
               :pattern/rationale "because"
               :pattern/source :llm}]
             result)))))
