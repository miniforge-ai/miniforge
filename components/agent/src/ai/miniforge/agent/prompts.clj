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

(ns ai.miniforge.agent.prompts
  "Agent prompt loading utilities.
   Loads system prompts from EDN resources for configurability."
  (:require
   [ai.miniforge.response.interface :as response]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Prompt loading

(defn load-prompt
  "Load an agent prompt from the resources directory.

   Arguments:
   - agent-type: Keyword identifying the agent (e.g., :implementer, :planner)

   Returns:
   - The system prompt string

   Throws:
   - ExceptionInfo if prompt cannot be loaded"
  [agent-type]
  (let [resource-path (str "prompts/" (name agent-type) ".edn")]
    (if-let [resource (io/resource resource-path)]
      (let [prompt-data (with-open [rdr (io/reader resource)]
                          (edn/read (java.io.PushbackReader. rdr)))]
        (or (:prompt/system prompt-data)
            (response/throw-anomaly! :anomalies/not-found
                                    "Prompt data missing :prompt/system key"
                                    {:agent-type agent-type
                                     :resource-path resource-path
                                     :keys (keys prompt-data)}))))
      (response/throw-anomaly! :anomalies/not-found
                                "Could not find prompt resource"
                                {:agent-type agent-type
                                 :resource-path resource-path})))))

(defn load-prompt-data
  "Load full prompt data map from resources.

   Arguments:
   - agent-type: Keyword identifying the agent

   Returns:
   - The full prompt data map including :prompt/id, :prompt/version, etc."
  [agent-type]
  (let [resource-path (str "prompts/" (name agent-type) ".edn")]
    (if-let [resource (io/resource resource-path)]
      (with-open [rdr (io/reader resource)]
        (edn/read (java.io.PushbackReader. rdr)))
      (response/throw-anomaly! :anomalies/not-found
                                "Could not find prompt resource"
                                {:agent-type agent-type
                                 :resource-path resource-path})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (load-prompt :implementer)
  (load-prompt :tester)
  (load-prompt :planner)
  (load-prompt :releaser)
  (load-prompt-data :implementer)
  :leave-this-here)
