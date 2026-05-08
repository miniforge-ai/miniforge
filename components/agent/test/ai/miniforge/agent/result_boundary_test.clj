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

(ns ai.miniforge.agent.result-boundary-test
  (:require
   [ai.miniforge.agent.result-boundary :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest normalize-llm-result-prefers-worktree-metadata
  (testing "worktree metadata wins over MCP artifact, fallback artifact, and parsed stdout"
    (let [normalized (sut/normalize-llm-result
                      {:role :implement
                       :response {:success false
                                  :content "{:status :already-implemented :summary \"stdout\"}"
                                  :error {:message "backend failed"}}
                       :worktree-artifacts {:implement {:status :already-implemented
                                                        :summary "worktree"}}
                       :artifact {:status :already-implemented :summary "mcp"}
                       :fallback-artifact {:status :already-implemented :summary "fallback"}
                       :parse-response read-string})]
      (is (= :worktree-metadata (:artifact-source normalized)))
      (is (= "worktree" (:summary (sut/authoritative-payload normalized))))
      (is (true? (sut/usable-content? normalized))))))

(deftest normalize-llm-result-accepts-parseable-content-from-failure
  (testing "parseable stdout still yields a usable boundary when backend success is false"
    (let [normalized (sut/normalize-llm-result
                      {:response {:success false
                                  :content "{:status :already-implemented :summary \"done\"}"
                                  :error {:message "Adaptive timeout"}}
                       :parse-response read-string})]
      (is (false? (:response-success? normalized)))
      (is (= :already-implemented (:status (:parsed-content normalized))))
      (is (true? (sut/usable-content? normalized))))))

(deftest error-response-preserves-backend-error-shape
  (testing "error-response carries raw backend data and response metadata through"
    (let [normalized {:llm-error {:message "Adaptive timeout"
                                  :type "adaptive_timeout"
                                  :raw-stdout "stream"}
                      :stop-reason :timeout
                      :num-turns 3
                      :tokens 17}
          result (sut/error-response normalized "LLM call failed")]
      (is (= :error (:status result)))
      (is (= "Adaptive timeout" (get-in result [:error :message])))
      (is (= "adaptive_timeout" (get-in result [:error :data :type])))
      (is (= "stream" (get-in result [:error :data :raw-stdout])))
      (is (= :timeout (get-in result [:error :data :stop-reason])))
      (is (= 3 (get-in result [:error :data :num-turns])))
      (is (= 17 (get-in result [:metrics :tokens]))))))
