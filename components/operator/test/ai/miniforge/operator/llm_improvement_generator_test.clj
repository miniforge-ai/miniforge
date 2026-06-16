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

(ns ai.miniforge.operator.llm-improvement-generator-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.llm.interface.protocols.llm-client :as llm-client]
            [ai.miniforge.operator.llm-improvement-generator :as sut]
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

(deftest generate-improvements-uses-public-llm-interface-test
  (testing "LLM improvement generator calls the client and parses JSON improvements"
    (let [requests (atom [])
          client (->FakeLLMClient {:success true
                                   :content "[{\"type\":\"prompt-change\",\"target\":\"reviewer\",\"change\":{\"prompt\":\"shorter\"},\"rationale\":\"clearer\",\"confidence\":0.8,\"source-pattern-type\":\"anti-pattern\"}]"}
                                  requests)
          generator (sut/create-llm-improvement-generator {:llm-client client})
          result (proto/generate-improvements generator
                                             [{:pattern/type :anti-pattern
                                               :pattern/affected :reviewer
                                               :pattern/occurrences 2
                                               :pattern/confidence 0.7
                                               :pattern/description "too much"
                                               :pattern/rationale "verbose"}]
                                             {:workflow-id "wf-1"})
          [improvement] result]
      (is (= 1 (count @requests)))
      (is (uuid? (:improvement/id improvement)))
      (is (= :prompt-change (:improvement/type improvement)))
      (is (= "reviewer" (:improvement/target improvement)))
      (is (= {:prompt "shorter"} (:improvement/change improvement)))
      (is (= "clearer" (:improvement/rationale improvement)))
      (is (= 0.8 (:improvement/confidence improvement)))
      (is (= :anti-pattern (:improvement/source-pattern-type improvement)))
      (is (= :proposed (:improvement/status improvement)))
      (is (= :llm (:improvement/source improvement))))))
