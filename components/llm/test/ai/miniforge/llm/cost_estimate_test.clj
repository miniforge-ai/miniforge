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
(ns ai.miniforge.llm.cost-estimate-test
  "An estimate only where the cost can be known (#2378)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.cost :as cost]
   [ai.miniforge.llm.cost-estimate :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} known-estimate-test
  (testing "complete usage on a priced model is estimated"
    (is (= (cost/estimate-cost {:input-tokens 1000 :output-tokens 100} "claude-sonnet-4-6")
           (sut/known-estimate {:input-tokens 1000 :output-tokens 100} "claude-sonnet-4-6"))))
  (testing "a reported zero still counts"
    (is (= 0.0 (sut/known-estimate {:input-tokens 0 :output-tokens 0} "claude-sonnet-4-6"))))
  (testing "no estimate without both counts, with cache tokens, or for an unpriced model"
    (is (nil? (sut/known-estimate {:output-tokens 100} "claude-sonnet-4-6")))
    (is (nil? (sut/known-estimate {} "claude-sonnet-4-6")))
    (is (nil? (sut/known-estimate {:input-tokens 1000 :output-tokens 100
                                   :cache-read-input-tokens 5000}
                                  "claude-sonnet-4-6")))
    (is (nil? (sut/known-estimate {:input-tokens 1000 :output-tokens 100}
                                  "claude-unpriced-test-model"))))
  (testing "Gemini 2.5 Pro's price holds up to 200k prompt tokens"
    (is (some? (sut/known-estimate {:input-tokens 200000 :output-tokens 10} "gemini-2.5-pro")))
    (is (nil? (sut/known-estimate {:input-tokens 200001 :output-tokens 10} "gemini-2.5-pro")))))
