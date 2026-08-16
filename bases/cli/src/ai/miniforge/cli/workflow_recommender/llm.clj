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
(ns ai.miniforge.cli.workflow-recommender.llm
  "LLM invocation and response parsing for workflow recommendation."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0

;; LLM interaction
(defn ^{:stratum 0} parse-llm-response
  "Parse JSON response from LLM.

   Arguments:
     response-text - String response from LLM

   Returns: Parsed map or nil"
  [response-text]
  (try
    ;; Try to extract JSON from response (may have markdown code blocks)
    ;; Use (?s) flag so . matches newlines — LLM JSON typically spans multiple lines
    (let [json-text (if (str/includes? response-text "```")
                      (second (re-find #"(?s)```(?:json)?\s*(\{.*?\})\s*```" response-text))
                      response-text)
          parsed (json/parse-string (or json-text response-text) true)]
      ;; Convert workflow string to keyword
      (update parsed :workflow keyword))
    (catch Exception e
      (println (messages/t :recommender/parse-warning {:error (ex-message e)}))
      nil)))

(defn ^{:stratum 0} call-llm-for-recommendation
  "Call LLM to get workflow recommendation.

   Arguments:
     llm-client - LLM client (from ai.miniforge.llm.interface)
     prompt - String prompt

   Returns: LLM response map or nil"
  [llm-client prompt]
  (when llm-client
    (try
      (let [result (llm/complete llm-client {:prompt prompt :max-tokens 500})]
        (when (:success result)
          (:content result)))
      (catch Exception e
        (println (messages/t :recommender/llm-failed-warning {:error (ex-message e)}))
        nil))))
