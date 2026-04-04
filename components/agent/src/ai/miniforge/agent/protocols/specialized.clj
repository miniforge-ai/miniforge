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

(ns ai.miniforge.agent.protocols.specialized
  "Internal protocol for specialized agents.

   This protocol is internal to the agent component and is not
   intended to be used by other components.")

(defprotocol SpecializedAgent
  "Protocol for specialized AI agents with functional invoke/validate/repair.
   This allows specialized agents to define their behavior via functions."

  (invoke [this context input]
    "Execute the agent's primary function on the input.
     Returns {:status :success/:error, :output <result>, :metrics {...}}")

  (validate [this output]
    "Validate the agent's output against its schema.
     Returns {:valid? bool, :errors [...] or nil}")

  (repair [this output errors context]
    "Attempt to repair invalid output based on validation errors.
     Returns {:status :success/:error, :output <repaired-result>}"))
