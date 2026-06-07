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

(ns ai.miniforge.agent.interface.llm
  "LLM-facing utility helpers for the agent public API."
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.protocols.impl.memory :as mem-impl]))

;------------------------------------------------------------------------------ Layer 0
;; Utility helpers

(def estimate-tokens
  "Estimate token count for message content. Arg: content (any). Returns an
   int: for strings, max(1, chars/4); 100 for non-string content."
  mem-impl/estimate-tokens)

(def estimate-cost
  "Estimate cost in USD for token usage. Args: input-tokens, output-tokens,
   model (string). Returns a double; unknown/unpriced models return 0.0."
  core/estimate-cost)

(def create-mock-llm
  "Create a mock LLMBackend record for testing. Arg (optional): a single
   response map or a sequence of them; defaults to a canned response.
   Returns a value satisfying the LLMBackend protocol."
  core/create-mock-llm)
