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

(ns ai.miniforge.llm.interface.protocols.llm-client
  "Public protocol for LLM client interaction.

   This is an extensibility point - users can implement custom LLM clients
   by implementing this protocol.")

(defprotocol LLMClient
  "Protocol for LLM interaction."
  (complete* [this request]
    "Send a completion request, returns result map.")
  (complete-stream* [this request on-chunk]
    "Send a streaming completion request, calls on-chunk for each token.

     Arguments:
     - request: Request map (same as complete*)
     - on-chunk: Callback function (fn [chunk-data])
                 Called with {:delta \"...\" :done? false} for each chunk
                 Called with {:delta \"\" :done? true :content \"full text\"} when complete

     Returns: Same format as complete* (final result)")
  (get-config [this]
    "Return client configuration."))
