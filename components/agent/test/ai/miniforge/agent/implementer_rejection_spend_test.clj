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
(ns ai.miniforge.agent.implementer-rejection-spend-test
  "A rejected implementer turn still reports the cost it consumed, not only
   its tokens."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.implementer.response :as impl-response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} spent-turn
  "A normalized implementer turn that consumed tokens and a known cost but
   produced nothing promotable."
  {:content "I looked at the repository."
   :structured-artifact nil
   :parsed-content nil
   :derived-artifact nil
   :artifact-source nil
   :tokens 900
   :cost-usd 0.0135
   :tools-called []})

(defn ^{:stratum 0} process
  [normalized input]
  (impl-response/process-llm-response normalized {} nil input))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} rejected-turn-keeps-its-cost-test
  (testing "parse-failed"
    (let [result (process spent-turn {})]
      (is (= :parse-failed (get-in result [:error :data :reject/reason])))
      (is (= 900 (get-in result [:metrics :tokens])))
      (is (= 0.0135 (get-in result [:metrics :cost-usd])))))
  (testing "metadata-only MCP submit"
    (let [result (process (assoc spent-turn
                                 :artifact-source :mcp
                                 :structured-artifact {:code/summary "metadata only"})
                          {})]
      (is (= :metadata-only-submit (get-in result [:error :data :reject/reason])))
      (is (= 0.0135 (get-in result [:metrics :cost-usd])))))
  (testing "unverified already-implemented claim on a repair attempt"
    (let [result (process (assoc spent-turn :parsed-content {:status :already-implemented
                                                             :summary "done"})
                          {:task/review-feedback "fix the test"})]
      (is (= :implementer/unverified-already-implemented
             (get-in result [:error :data :code])))
      (is (= 0.0135 (get-in result [:metrics :cost-usd]))))))

(deftest ^{:stratum 1} rejected-turn-without-a-known-cost-reports-none-test
  (testing "an unknown cost stays absent rather than becoming 0"
    (let [result (process (dissoc spent-turn :cost-usd) {})]
      (is (= 900 (get-in result [:metrics :tokens])))
      (is (not (contains? (:metrics result) :cost-usd))))))
